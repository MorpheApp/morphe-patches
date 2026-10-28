package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CaptionInputReplayTest {

    @Test
    public void preservesEveryInputByteIncludingNewlines() throws Exception {
        byte[] bytes = "<p>A\n\nB\n</p>".getBytes("UTF-8");
        CaptionInputReplay.Copy copy =
                CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 1024);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, copy.bytes);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void oversizedInputStillReachesParserUnchanged() throws Exception {
        byte[] bytes = new byte[8192];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 31);
        assertFalse(copy.complete);
        assertTrue(copy.bytes.length <= 32);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void preservesEmptyAndExactLimitInput() throws Exception {
        assertTrue(CaptionInputReplay.copy(new ByteArrayInputStream(new byte[0]), 10).complete);
        byte[] bytes = new byte[10];
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 10);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test(expected = IOException.class)
    public void preservesReadFailures() throws Exception {
        CaptionInputReplay.copy(
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("test failure");
                    }
                },
                1024);
    }

    @Test
    public void sharedTrackRoutingRepairsInputAndRetainsParserOwnedClose() throws Exception {
        byte[] source =
                ("<timedtext format='3'><head><ws id='1' mh='2'/></head>"
                                + "<body><w t='0' id='1' ws='1'/>"
                                + "<p t='0' d='2000' w='1'>one</p>"
                                + "<p t='1000' d='1000' w='1' a='1'>\n</p>"
                                + "<p t='1500' d='2000' w='1' a='1'>\n</p>"
                                + "<p t='2000' d='4000' w='1'>two</p></body></timedtext>")
                        .getBytes("UTF-8");
        boolean[] closed = {false};
        InputStream original =
                new ByteArrayInputStream(source) {
                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
        Track track = new Track();
        TranslatedCaptionPensPatch.setParsedRequest(
                (TranslatedCaptionPensPatch.CaptionRequestInterface) () -> track);
        assertEquals(1, track.vssReads);
        InputStream repaired = TranslatedCaptionPensPatch.repairInput(original);
        assertEquals(2, CaptionXml.read(read(repaired)).getElementsByTagName("p").getLength());
        assertFalse(closed[0]);
        repaired.close();
        assertTrue(closed[0]);
        // A non-caption request must clear the previous translated decoder state.
        TranslatedCaptionPensPatch.setParsedRequest(null);
        InputStream next = new ByteArrayInputStream(source);
        assertSame(next, TranslatedCaptionPensPatch.repairInput(next));
    }

    @Test
    public void failedSourceGetterClearsRepairAndPreservesTranslatedStyle() throws Exception {
        Track track = new Track();
        TranslatedCaptionPensPatch.CaptionRequestInterface request = () -> track;
        try {
            TranslatedCaptionPensPatch.setParsedRequest(request);
            track.urlUnavailable = true;
            TranslatedCaptionPensPatch.setParsedRequest(request);
            assertEquals(Integer.MIN_VALUE, TranslatedCaptionPensPatch.getPenId(7));
            InputStream input = new ByteArrayInputStream(new byte[] {1});
            assertSame(input, TranslatedCaptionPensPatch.repairInput(input));
        } finally {
            TranslatedCaptionPensPatch.setParsedRequest(null);
        }
    }

    private static final class Track implements AutoTranslateCaptionsPatch.CaptionTrackInterface {
        int vssReads;
        boolean urlUnavailable;
        @Override
        public String patch_getLanguageCode() {
            return "de";
        }

        @Override
        public String patch_getVssId() {
            vssReads++;
            return "ta.en.de";
        }

        @Override
        public String patch_getUrl() {
            if (urlUnavailable) throw new NoSuchMethodError("URL getter unavailable");
            return "https://www.youtube.com/api/timedtext?kind=asr&lang=en&tlang=de";
        }

        @Override
        public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(
                String languageCode, String url, String vssId, CharSequence label) {
            throw new UnsupportedOperationException();
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[113];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
