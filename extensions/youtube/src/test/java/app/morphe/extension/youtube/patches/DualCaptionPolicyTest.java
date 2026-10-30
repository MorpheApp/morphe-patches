package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class DualCaptionPolicyTest {
    private static org.w3c.dom.Document doc(String body) {
        return CaptionXml.read(
                ("<timedtext format='3'><head/><body><w t='0' id='1'/>"
                                + body
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String p(int start, int duration, String text) {
        return "<p t='" + start + "' d='" + duration + "' w='1'>" + text + "</p>";
    }

    @Test
    public void eachLanguageKeepsItsOwnBoundaries() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 2000, "first") + p(2000, 2000, "second")),
                        doc(p(0, 4000, "translation")));
        assertNotNull(policy);
        assertEquals(
                "first", StableCaptionBlockPolicy.at(policy.plan(s -> s.length() < 10), 1000).text);
        assertEquals(
                "second",
                StableCaptionBlockPolicy.at(policy.plan(s -> s.length() < 10), 3000).text);
        var secondary = policy.secondary.plan(s -> true);
        assertSame(
                StableCaptionBlockPolicy.at(secondary, 1000),
                StableCaptionBlockPolicy.at(secondary, 3000));
    }

    @Test
    public void aSecondaryParagraphDoesNotRequireAnExactPrimaryInterval() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 2000, "hello")), doc(p(0, 3000, "merhaba")));
        assertNotNull(StableCaptionBlockPolicy.at(policy.secondary.plan(s -> true), 2500));
        assertEquals(-1, policy.templateTime(2500));
        assertEquals(0, policy.displayTemplateTime(2500));
    }

    @Test
    public void onlySecondaryVisibleBeforePrimaryDoesNotExposeFuturePrimaryText() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(2000, 2000, "hello")), doc(p(0, 4000, "merhaba")));
        assertEquals(-1, policy.templateTime(1000));
        assertEquals(2000, policy.displayTemplateTime(1000));
        assertNull(StableCaptionBlockPolicy.at(policy.plan(s -> true), 1000));
        assertNotNull(StableCaptionBlockPolicy.at(policy.secondary.plan(s -> true), 1000));
    }

    @Test
    public void explicitClearsAreIndependent() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 5000, "hello") + p(1000, 1000, "")),
                        doc(p(0, 5000, "merhaba") + p(3000, 1000, "")));
        assertNull(StableCaptionBlockPolicy.at(policy.plan(s -> true), 2000));
        assertNotNull(StableCaptionBlockPolicy.at(policy.secondary.plan(s -> true), 2000));
        assertEquals(-1, policy.displayTemplateTime(4000));
    }

    @Test
    public void sourceSnapshotsAreImmutable() {
        var document = doc(p(0, 4000, "hello"));
        var policy = StableCaptionBlockPolicy.parsePairs(document, doc(p(0, 4000, "merhaba")));
        document.getElementsByTagName("p").item(0).setTextContent("mutated");
        assertEquals("hello", policy.plan(s -> true).get(0).text);
    }

    @Test
    public void oneOversizedLanguageDoesNotDisableTheOther() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 4000, "long source phrase")), doc(p(0, 4000, "short")));
        assertFalse(policy.plan(s -> s.length() < 10).get(0).fits);
        assertTrue(policy.secondary.plan(s -> s.length() < 10).get(0).fits);
    }

    @Test
    public void secondaryBoundariesInvalidateFrameIdentityWhilePrimaryIsUnchanged() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 4000, "hello")), doc(p(0, 2000, "one") + p(2000, 2000, "two")));
        assertNotEquals(policy.frameKey(1000, 0), policy.frameKey(3000, 0));
    }

    @Test
    public void punctuationDoesNotBecomeAStandaloneFlash() {
        var policy =
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(66000, 4000, "hello")),
                        doc(p(66000, 2000, "merhaba") + p(68000, 2000, "?")));
        var blocks = policy.secondary.plan(s -> true);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).text.endsWith("?"));
    }

    @Test
    public void preservesRtlCjkAndOtherScripts() {
        for (String[] pair :
                new String[][] {
                    {"Hallo Welt", "こんにちは世界"},
                    {"مرحبا بالعالم", "Bonjour le monde"},
                    {"你好世界", "Hello world"}
                }) {
            var policy =
                    StableCaptionBlockPolicy.parsePairs(
                            doc(p(0, 4000, pair[0])), doc(p(0, 4000, pair[1])));
            assertEquals(pair[0], policy.plan(s -> true).get(0).text);
            assertEquals(pair[1], policy.secondary.plan(s -> true).get(0).text);
        }
    }

    @Test
    public void invalidDocumentsRemainNative() {
        assertNull(StableCaptionBlockPolicy.parsePairs(null, doc(p(0, 2000, "hello"))));
        assertNull(
                StableCaptionBlockPolicy.parsePairs(
                        doc(p(0, 2000, "<b>styled</b>")), doc(p(0, 2000, "hello"))));
    }

    @Test
    public void manualPositionsAreNotEligibleForDualReflow() {
        var positioned = doc(p(0, 4000, "hello"));
        var head = positioned.getElementsByTagName("head").item(0);
        var position = positioned.createElement("wp");
        position.setAttribute("id", "1");
        position.setAttribute("ah", "10");
        position.setAttribute("av", "10");
        head.appendChild(position);
        assertNotNull(StableCaptionBlockPolicy.parse(positioned));
        assertNull(StableCaptionBlockPolicy.parseManual(positioned));
        var inline = doc(p(0, 4000, "hello"));
        ((org.w3c.dom.Element) inline.getElementsByTagName("w").item(0)).setAttribute("wp", "1");
        assertNull(StableCaptionBlockPolicy.parseManual(inline));
    }

    @Test
    public void plainManualAndAutomaticWindowStylesRemainEligible() {
        assertNotNull(StableCaptionBlockPolicy.parseManual(doc(p(0, 4000, "hello"))));
        var automatic = doc(p(0, 4000, "hello"));
        automatic.getElementsByTagName("head").item(0).appendChild(automatic.createElement("ws"));
        assertNotNull(StableCaptionBlockPolicy.parse(automatic));
        assertNull(StableCaptionBlockPolicy.parseManual(automatic));
    }
}
