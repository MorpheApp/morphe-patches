package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class StableCaptionBlockPolicyTest {
    static byte[] xml(String body) {
        return ("<timedtext format='3'><head><ws id='1' sd='3'/></head><body><w t='0' id='1'/>"
                        + body
                        + "</body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String p(int t, int d, String s) {
        return "<p t='" + t + "' d='" + d + "' w='1'>" + s + "</p>";
    }

    static String s(int t, String text) {
        return "<s t='" + t + "'>" + text + "</s>";
    }

    private static StableCaptionBlockPolicy parse(byte[] input) {
        return StableCaptionBlockPolicy.parse(CaptionXml.read(input));
    }

    static StableCaptionBlockPolicy policy(String body) {
        StableCaptionBlockPolicy p = parse(xml(body));
        assertNotNull(p);
        return p;
    }

    @Test
    public void alreadyCenteredFrameRetainsItsNativeList() {
        var frame = new StableCaptionBlocks.Frame(new StableCaptionBlocks.State(), 0, 0);
        var cue = new StableCaptionBlocks.Cue() {
            @Override public CharSequence patch_getOriginalCaptionVisible() { return "native"; }
            @Override public StableCaptionBlocks.Frame patch_getBlockFrame() { return frame; }
            @Override public Object patch_copyBlockFrame(StableCaptionBlocks.Frame requested) {
                assertSame(frame, requested);
                return this;
            }
        };
        List<?> original = List.of(cue);
        assertSame(original, StableCaptionBlocks.centerBlocks(original));
    }

    @Test
    public void sharedDocumentKeepsSourcePlanWhenRepairRemovesOrphanEvents() {
        byte[] source =
                ("<timedtext format='3'><head><ws id='1' mh='2'/></head>"
                                + "<body><w t='0' id='1' ws='1'/>"
                                + p(0, 2000, "one")
                                + "<p t='1000' d='1000' w='1' a='1'>\n</p>"
                                + "<p t='1500' d='2000' w='1' a='1'>\n</p>"
                                + p(2000, 4000, "two")
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(source);
        assertNotNull(document);
        var policy = StableCaptionBlockPolicy.parse(document);
        assertNotNull(policy);
        var before = policy.plan(text -> true);
        byte[] repaired = TranslatedRollingCaptionPolicy.repair(source, document);
        assertNotSame(source, repaired);
        assertEquals(2, document.getElementsByTagName("p").getLength());
        assertEquals(4, CaptionXml.read(source).getElementsByTagName("p").getLength());
        var after = policy.plan(text -> true);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).start, after.get(i).start);
            assertEquals(before.get(i).end, after.get(i).end);
            assertEquals(before.get(i).text, after.get(i).text);
        }
        assertSame(repaired, TranslatedRollingCaptionPolicy.repair(repaired, CaptionXml.read(repaired)));
        assertSame(source, TranslatedRollingCaptionPolicy.repair(source, null));
    }

    @Test
    public void preservesSeparatelyTimedWhitespaceWithoutInventingWordTimes() {
        var policy = policy(p(0, 4000, s(0, "hello") + s(500, " ") + s(1000, "world")));
        var blocks = policy.plan(text -> true);
        assertEquals("hello world", blocks.get(0).text);
        assertEquals(0, blocks.get(0).start);
        assertEquals(1000, policy.templateTime(1100));
    }

    @Test
    public void joinsAdjacentSourceParagraphsIntoOneReadableBlock() {
        var p =
                policy(
                        p(0, 4000, s(0, "one ") + s(1000, "two"))
                                + p(2000, 5000, s(0, "three ") + s(1000, "four")));
        var b = p.plan(text -> text.length() <= 24);
        assertEquals(1, b.size());
        assertEquals("one two three four", b.get(0).text);
        assertEquals(0, b.get(0).start);
        assertEquals(7000, b.get(0).end);
    }

    @Test
    public void usesSourceBoundariesAndKeepsWholeBlockStable() {
        var p =
                policy(
                        p(
                                0,
                                6000,
                                s(0, "one ")
                                        + s(1000, "two ")
                                        + s(2000, "three ")
                                        + s(3000, "four ")
                                        + s(4000, "five")));
        var b = p.plan(text -> text.length() <= 14);
        assertTrue(b.size() > 1);
        for (var block : b) {
            assertEquals(0, block.start % 1000);
            assertTrue(block.fits);
            assertTrue(block.text.length() <= 14);
            assertTrue(block.end > block.start);
        }
        assertEquals(
                "one two three four five",
                String.join(" ", b.stream().map(x -> x.text).toArray(String[]::new)));
    }

    @Test
    public void redistributesShortTailWhenBothPiecesStillFit() {
        var p =
                policy(
                        p(
                                0,
                                6500,
                                s(0, "a ")
                                        + s(1500, "b ")
                                        + s(3000, "c ")
                                        + s(4500, "d ")
                                        + s(6000, "e")));
        var b = p.plan(text -> text.length() <= 7);
        assertEquals(2, b.size());
        assertTrue(b.get(0).end - b.get(0).start >= 1500);
        assertTrue(b.get(1).end - b.get(1).start >= 1500);
    }

    @Test
    public void sameTimeAtomsCannotBecomeTwoDifferentBlocks() {
        var p = policy(p(0, 4000, s(0, "one ") + s(0, "two ") + s(2000, "three")));
        var b = p.plan(text -> text.length() <= 5);
        assertEquals("one two", b.get(0).text);
        assertFalse(b.get(0).fits);
        assertEquals(2000, b.get(1).start);
    }

    @Test
    public void sourceGapsStayEmptyAndPreviewIsBounded() {
        var p =
                policy(
                        p(0, 1000, s(0, "first"))
                                + p(4000, 2000, s(0, "second"))
                                + p(20000, 1000, s(0, "third")));
        var b = p.plan(text -> true);
        assertEquals(3, b.size());
        assertEquals(1000, b.get(0).end);
        assertEquals(4000, b.get(1).start);
        var slow = policy(p(0, 14000, s(0, "first ") + s(10000, "second"))).plan(text -> true);
        assertEquals(2, slow.size());
        assertEquals(10000, slow.get(0).end);
    }

    @Test
    public void preservesUnicodeWithoutSplittingAtomicText() {
        for (String text : List.of("שלום עולם", "مرحبا بالعالم", "日本語字幕", "👩🏽‍💻 merhaba")) {
            var b = policy(p(0, 3000, s(0, text))).plan(value -> true);
            assertEquals(text, b.get(0).text);
        }
    }

    @Test
    public void unsupportedStructureFallsBackBeforePresentation() {
        assertNull(parse(xml("<w t='1' id='2'/>" + p(0, 1000, "hello"))));
        assertNull(parse(xml(p(0, 1000, "<b>styled</b>"))));
        assertNull(parse(xml(p(0, 1000, s(500, "a ") + s(100, "b")))));
    }

    @Test
    public void recognizesOnlyVerifiedAutomaticTracks() {
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "a.en", "https://www.youtube.com/api/timedtext?kind=asr"));
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "ta.en.tr", "https://www.youtube.com/api/timedtext?kind=asr&tlang=tr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        ".en", "https://www.youtube.com/api/timedtext?kind=asr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "a.en", "https://evil.example/api/timedtext?kind=asr"));
    }

    @Test
    public void planIsSeekableAndDoesNotInventSubspanTimes() {
        var p = policy(p(0, 16000, s(0, "a ") + s(4000, "b ") + s(8000, "c ") + s(12000, "d")));
        var plan = p.plan(text -> true);
        assertEquals(2, plan.size());
        assertSame(plan.get(0), StableCaptionBlockPolicy.at(plan, 100));
        assertSame(plan.get(1), StableCaptionBlockPolicy.at(plan, 12500));
        assertSame(plan.get(0), StableCaptionBlockPolicy.at(plan, 100));
        assertNull(StableCaptionBlockPolicy.at(plan, 16000));
        assertEquals(
                "a b c d", String.join(" ", plan.stream().map(b -> b.text).toArray(String[]::new)));
    }

    @Test
    public void longUnbreakableSegmentIsMarkedForNativeFallback() {
        var plan = policy(p(0, 4000, s(0, "unbreakable"))).plan(text -> false);
        assertEquals(1, plan.size());
        assertFalse(plan.get(0).fits);
        assertEquals("unbreakable", plan.get(0).text);
    }

    @Test
    public void styledAndOversizedAtomicSourcesRemainNative() {
        String styled =
                "<timedtext format='3'><head><pen id='1' b='1'/></head><body><w t='0' id='1'/>"
                        + p(0, 1000, "style")
                        + "</body></timedtext>";
        assertNull(parse(styled.getBytes(StandardCharsets.UTF_8)));
        assertNull(
                parse(
                        xml(p(0, 1000, s(0, "x".repeat(3000)) + s(0, "y".repeat(3000))))));
    }

    @Test
    public void sourcePunctuationIsNotSeparatedIntoAnOrphanToken() {
        var plan =
                policy(p(0, 3000, "hello") + p(1000, 3000, ".") + p(2000, 3000, "world"))
                        .plan(text -> true);
        assertEquals("hello. world", plan.get(0).text);
    }

    @Test
    public void emptySourceParagraphCreatesAnActualGap() {
        var p =
                policy(
                        p(0, 5000, s(0, "first"))
                                + p(1000, 2000, "")
                                + p(3000, 2000, s(0, "second")));
        var blocks = p.plan(text -> true);
        assertEquals(2, blocks.size());
        assertEquals(1000, blocks.get(0).end);
        assertNull(StableCaptionBlockPolicy.at(blocks, 2000));
    }

    @Test
    public void balancesReadingLoadBeforeDenseTranslationAfterShortGap() {
        String first =
                s(0, "Aksi ")
                        + s(320, "takdirde, ")
                        + s(640, "geri ")
                        + s(960, "kalanını ")
                        + s(1280, "olduğu ")
                        + s(1600, "gibi ")
                        + s(1920, "bırakın");
        String[] words =
                "bunu yapmanıza izin vermeden önce Windows'taki yönetici erişimine kabaca eşdeğer olan sudo'yu gerektirerek size bunu hatırlatacaktır."
                        .split(" ");
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < words.length; i++) dense.append(s(i * 101, words[i] + " "));
        var p =
                policy(
                        p(505359, 4400, first)
                                + p(507440, 4959, s(0, "ve ") + s(2159, "Linux,"))
                                + p(514159, 3281, dense.toString()));
        var blocks = p.plan(text -> text.length() <= 110);
        double worst = 0;
        for (var b : blocks) worst = Math.max(worst, b.text.length() * 1000.0 / (b.end - b.start));
        assertTrue("dense translation must borrow reading time, worst=" + worst, worst < 40);
    }

    @Test
    public void borrowsShortUnclearedGapButNeverRevivesExplicitClear() {
        var p = policy(p(0, 1000, "first") + p(2500, 1000, "second"));
        var blocks = p.plan(text -> text.length() <= 6);
        assertEquals(2500, blocks.get(0).end);
        assertEquals(0, p.templateTime(2000));
        assertNotNull(StableCaptionBlockPolicy.at(blocks, 2000));
        var cleared = policy(p(0, 3000, "first") + p(1000, 1000, "") + p(2500, 1000, "second"));
        assertEquals(-1, cleared.templateTime(2000));
        assertNull(StableCaptionBlockPolicy.at(cleared.plan(text -> true), 2000));
        assertEquals(-1, cleared.templateTime(-1));
        assertEquals(-1, cleared.templateTime(4000));
    }

    @Test
    public void neverCutsTimedPartsInsideOneWord() {
        var blocks =
                policy(p(0, 3000, s(0, "win") + s(1000, "dows") + s(2000, ".")))
                        .plan(text -> text.length() <= 4);
        assertEquals(1, blocks.size());
        assertEquals("windows.", blocks.get(0).text);
        assertFalse(blocks.get(0).fits);
    }
}
