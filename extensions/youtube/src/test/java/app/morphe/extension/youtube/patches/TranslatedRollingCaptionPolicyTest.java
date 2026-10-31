package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class TranslatedRollingCaptionPolicyTest {
    private static byte[] repair(byte[] input) {
        return TranslatedRollingCaptionPolicy.repair(input, CaptionXml.read(input));
    }

    private static byte[] utf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String document(String body) {
        return "<timedtext format='3'><head><ws id='1' mh='2'/></head><body><w id='1' ws='1'/>"
                + body
                + "</body></timedtext>";
    }

    private static String p(int t, int d, String text) {
        return "<p t='" + t + "' d='" + d + "' w='1'>" + text + "</p>";
    }

    private static String br(int t, int d) {
        return "<p t='" + t + "' d='" + d + "' w='1' a='1'>\n</p>";
    }

    private static List<String> paragraphs(byte[] bytes) throws Exception {
        NodeList ps =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(new ByteArrayInputStream(bytes))
                        .getElementsByTagName("p");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ps.getLength(); i++) {
            Element e = (Element) ps.item(i);
            out.add(
                    e.getAttribute("t")
                            + "|"
                            + e.getAttribute("d")
                            + "|"
                            + e.getAttribute("w")
                            + "|"
                            + e.getAttribute("a")
                            + "|"
                            + e.getTextContent());
        }
        return out;
    }

    @Test
    public void removesCapturedOrphanRunsButKeepsNormalTransitionsAndSpans() throws Exception {
        String a = p(8080, 4400, "<s t='0' p='3'>A &amp; X</s>");
        String b = p(12480, 5360, "<s t='0' p='4'>B</s><s t='226'> C</s>");
        String c = p(17840, 5519, "<s>Türkçe 😀</s>");
        String d = p(20160, 5199, "D");
        byte[] source =
                utf(
                        document(
                                a
                                        + br(11030, 1450)
                                        + br(12470, 2970)
                                        + b
                                        + br(15430, 2410)
                                        + br(17830, 2330)
                                        + c
                                        + br(20150, 3209)
                                        + d));
        byte[] fixed = repair(source);
        assertNotSame(source, fixed);
        assertEquals(paragraphs(utf(document(a + b + c + br(20150, 3209) + d))), paragraphs(fixed));
        String text = new String(fixed, StandardCharsets.UTF_8);
        assertTrue(text.contains("p=\"3\""));
        assertTrue(text.contains("p=\"4\""));
        assertTrue(text.contains("t=\"226\""));
        assertSame(fixed, repair(fixed));
    }

    @Test
    public void preservesLegitimateSingleNewlineByteForByte() {
        byte[] source = utf(document(p(100, 1000, "A") + br(490, 610) + p(500, 1000, "B")));
        assertSame(source, repair(source));
    }

    @Test
    public void preservesActualNewlinesInsideText() {
        byte[] source = utf(document(p(100, 1000, "A\n\nB")));
        assertSame(source, repair(source));
    }

    @Test
    public void doesNotMixInterleavedWindows() throws Exception {
        String other = "<w id='2' ws='1'/><p t='11100' d='50' w='2'>Other</p>";
        byte[] source =
                utf(
                        document(
                                p(8080, 4400, "A")
                                        + br(11030, 1450)
                                        + other
                                        + br(12470, 2970)
                                        + p(12480, 5360, "B")));
        List<String> fixed = paragraphs(repair(source));
        assertEquals(3, fixed.size());
        assertTrue(fixed.toString().contains("Other"));
    }

    @Test
    public void doesNotTouchNonRollingOrUnknownWindows() {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        for (String xml :
                new String[] {
                    document(body).replace("mh='2'", "mh='0'"),
                    document(body).replace("<w id='1' ws='1'/>", ""),
                    document(body).replace("<ws id='1' mh='2'/>", "")
                }) {
            byte[] b = utf(xml);
            assertSame(b, repair(b));
        }
    }

    @Test
    public void ambiguousWindowDefinitionsAreUnchanged() {
        byte[] source =
                utf(
                        document(
                                "<w id='1' ws='1'/>"
                                        + p(8080, 4400, "A")
                                        + br(11030, 1450)
                                        + br(12470, 2970)
                                        + p(12480, 5360, "B")));
        assertSame(source, repair(source));
    }

    @Test
    public void missingOrBlankMetadataNeverIdentifiesRollingWindows() {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        String source = document(body);
        for (String xml :
                new String[] {
                    source.replace("<ws id='1' mh='2'/>", "<ws mh='2'/>")
                            .replace("<w id='1' ws='1'/>", "<w id='1'/>"),
                    source.replace("<ws id='1' mh='2'/>", "<ws id='' mh='2'/>")
                            .replace("ws='1'", "ws=''"),
                    source.replace("<ws id='1' mh='2'/>", "<ws id=' ' mh='2'/>")
                            .replace("ws='1'", "ws=' '"),
                    source.replace("<w id='1' ws='1'/>", "<w id='1' ws='1'/><w id='1'/>"),
                    source.replace("id='1' ws='1'", "id='' ws='1'")
                }) {
            byte[] bytes = utf(xml);
            assertSame(xml, bytes, repair(bytes));
        }
    }

    @Test
    public void preservesRunsWithUnknownAttributesOrActivePriorText() {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        for (String s :
                new String[] {
                    body.replace("a='1'", "a='1' p='3'"),
                    body.replace("t='8080' d='4400'", "t='8080' d='5000'"),
                    body.replace("t='11030' d='1450'", "t='11030' d='1440'")
                }) {
            byte[] b = utf(document(s));
            assertSame(b, repair(b));
        }
    }

    @Test
    public void malformedTimesAndUnterminatedRunsAreUnchanged() {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        for (String s :
                new String[] {
                    body.replace("t='12470'", "t='invalid'"),
                    body.replace("d='2970'", "d='-1'"),
                    p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970)
                }) {
            byte[] b = utf(document(s));
            assertSame(b, repair(b));
        }
    }

    @Test
    public void malformedUnknownAndDoctypeInputsPassThrough() {
        for (String s :
                new String[] {
                    "<bad>",
                    "<timedtext format='2'/>",
                    "<!DOCTYPE timedtext [<!ENTITY x SYSTEM 'file:///not-allowed'>]><timedtext"
                        + " format='3'>&x;</timedtext>"
                }) {
            byte[] b = utf(s);
            assertSame(b, repair(b));
        }
    }

    @Test
    public void unrelatedUntimedAppendDoesNotDisableEarlierRepair() throws Exception {
        String a = p(8080, 4400, "A"), b = p(12480, 5360, "B");
        String untimed = "<p t='92550' w='1' a='1'>\n</p>";
        byte[] source = utf(document(a + br(11030, 1450) + br(12470, 2970) + b + untimed));
        assertEquals(paragraphs(utf(document(a + b + untimed))), paragraphs(repair(source)));
    }

    @Test
    public void requestScopeRecognizesBothOriginalAndTranslatedAsr() {
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "ta.en.tr",
                        "https://www.youtube.com/api/timedtext?v=any&kind=asr&tlang=tr"));
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "a.en", "https://www.youtube.com/api/timedtext?v=any&kind=asr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "t.en.tr", "https://www.youtube.com/api/timedtext?v=any&tlang=tr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "ta.en.tr", "https://example.org/api/timedtext?kind=asr&tlang=tr"));
    }

    @Test
    public void automaticSourceUsesCanonicalIdentityValidation() {
        String base = "https://www.youtube.com/api/timedtext?kind=asr&tlang=de";
        assertTrue(AutoTranslateCaptionsPatch.isAutomaticCaption("ta.en.de", base));
        for (String url :
                new String[] {
                    base + "&kind=asr",
                    base + "&v=one&v=two",
                    base.replace("kind=", "%6bind="),
                    base.replace("/timedtext?", "/%74imedtext?"),
                    base.replace("tlang=de", "tlang=fr"),
                    base.replace("www.youtube.com", "www.youtube.com.example.org")
                }) assertFalse(url, AutoTranslateCaptionsPatch.isAutomaticCaption("ta.en.de", url));
    }

    @Test
    public void nonUtf8XmlNeverReinterpretsTheValidatedBytes() throws Exception {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        byte[] encoded =
                ("<?xml version='1.0' encoding='CP037'?>" + document(body))
                        .getBytes(java.nio.charset.Charset.forName("CP037"));
        assertSame(encoded, repair(encoded));
        byte[] declared = utf("<?xml version='1.0' encoding='ISO-8859-1'?>" + document(body));
        assertSame(declared, repair(declared));
    }

    @Test
    public void encodedExternalEntityIsNeverExpanded() throws Exception {
        java.nio.file.Path marker = java.nio.file.Files.createTempFile("caption-entity-", ".txt");
        try {
            java.nio.file.Files.write(marker, utf("PRIVATE_TEST_MARKER"));
            String xml =
                    "<?xml version='1.0' encoding='CP037'?>"
                            + "<!DOCTYPE timedtext [<!ENTITY x SYSTEM '"
                            + marker.toUri()
                            + "'>]>"
                            + document(
                                    p(8080, 4400, "&x;")
                                            + br(11030, 1450)
                                            + br(12470, 2970)
                                            + p(12480, 5360, "B"));
            byte[] source = xml.getBytes(java.nio.charset.Charset.forName("CP037"));
            assertSame(source, repair(source));
        } finally {
            java.nio.file.Files.delete(marker);
        }
    }

    @Test
    public void utf8XmlDeclarationAndBomStillAllowRepair() {
        String body = p(8080, 4400, "A") + br(11030, 1450) + br(12470, 2970) + p(12480, 5360, "B");
        for (String prefix :
                new String[] {
                    "<?xml version='1.0' encoding='UTF-8'?>",
                    "\uFEFF<?xml version='1.0' encoding='utf-8'?>"
                }) {
            byte[] source = utf(prefix + document(body));
            assertNotSame(source, repair(source));
        }
    }
}
