package app.morphe.extension.youtube.patches.captions;

import org.junit.Test;
import java.util.concurrent.*;
import app.morphe.extension.youtube.patches.captions.CaptionTranslationList.CaptionTrack;
import static org.junit.Assert.*;

public class CaptionPenStylesTest {
    private static final String ORIGINAL =
            "https://www.youtube.com/api/timedtext?v=PlsODqqqwFs&lang=en&sig=a%2Bb";
    private static final int DEFAULT_PEN = Integer.MIN_VALUE;

    static final class Track implements CaptionTrack {
        final String language, url, vss;
        Track(String language, String url, String vss) {
            this.language = language;
            this.url = url;
            this.vss = vss;
        }
        public String universalCaptions_getLanguageCode() { return language; }
        public String universalCaptions_getVssId() { return vss; }
        public String universalCaptions_getCaptionUrl() { return url; }
        public CharSequence universalCaptions_getCaptionLabel() { return language; }
        public CaptionTrack universalCaptions_copyTranslation(
                String language, String url, String vss, CharSequence label) {
            return new Track(language, url, vss);
        }
    }

    static final class Carrier implements CaptionPenStyles.CaptionCarrier {
        final CaptionTrack track;
        Carrier(CaptionTrack track) { this.track = track; }
        public CaptionTrack universalCaptions_styleTrack() { return track; }
    }

    private static Carrier original() { return new Carrier(new Track("en", ORIGINAL, ".en")); }
    private static Carrier translated() {
        return new Carrier(new Track("tr", ORIGINAL + "&tlang=tr", "t.en.tr"));
    }

    private static void within(Object request, Runnable test) {
        CaptionPenStyles.beginCaption(request);
        try {
            test.run();
        } finally {
            CaptionPenStyles.endCaption(request);
        }
    }

    @Test public void translatedAndOriginalStylesAreScopedToTheirOwnDocuments() {
        assertEquals(7, CaptionPenStyles.getPenId(7));
        within(translated(), () -> {
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(7));
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(0));
        });
        assertEquals(7, CaptionPenStyles.getPenId(7));
        within(original(), () -> assertEquals(7, CaptionPenStyles.getPenId(7)));
    }

    @Test public void invalidOrAmbiguousTargetsKeepOriginalPenIds() {
        for (String url : new String[] {
                ORIGINAL + "&tlang=",
                ORIGINAL + "&tlang=tr&tlang=de",
                ORIGINAL + "&tlang=tr&%74lang=de",
                ORIGINAL + "&tlang=tr%26lang%3Dde",
                ORIGINAL + "&tlang=bad%20value",
                "https://youtube.com.evil.example/api/timedtext?v=PlsODqqqwFs&lang=en&tlang=tr"
        }) {
            within(new Carrier(new Track("tr", url, "t.en.tr")),
                    () -> assertEquals(url, 8, CaptionPenStyles.getPenId(8)));
        }
        assertEquals(8, CaptionPenStyles.getPenId(8));
    }

    @Test public void unknownContextNeverInheritsTranslatedStyle() {
        within(translated(), () -> {
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(3));
            within(new Object(), () -> assertEquals(3, CaptionPenStyles.getPenId(3)));
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(3));
        });
        assertEquals(3, CaptionPenStyles.getPenId(3));
    }

    @Test public void staleExceptionHandlerCannotCloseAnUnrelatedScope() {
        Carrier outer = translated();
        Carrier inner = original();
        within(outer, () -> {
            CaptionPenStyles.endCaption(inner); // handler before inner begin
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(5));
            within(inner, () -> {
                CaptionPenStyles.endCaption(outer); // handler for the other request
                assertEquals(5, CaptionPenStyles.getPenId(5));
            });
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(5));
        });
        CaptionPenStyles.endCaption(outer); // repeated cleanup
        assertEquals(5, CaptionPenStyles.getPenId(5));
    }

    @Test public void exceptionExitRestoresTheOuterScope() {
        Carrier outer = translated();
        Carrier inner = original();
        within(outer, () -> {
            assertThrows(IllegalStateException.class, () ->
                within(inner, () -> {
                    assertEquals(11, CaptionPenStyles.getPenId(11));
                    throw new IllegalStateException("parser failed");
                }));
            assertEquals(DEFAULT_PEN, CaptionPenStyles.getPenId(11));
        });
        assertEquals(11, CaptionPenStyles.getPenId(11));
    }

    @Test public void sourceAndTargetIdentitiesMustAgree() {
        within(new Carrier(new Track("tr", ORIGINAL + "&tlang=tr", "t.en.fr")),
                () -> assertEquals(5, CaptionPenStyles.getPenId(5)));
    }

    @Test public void concurrentOriginalAndTranslatedParsersDoNotBleedAcrossThreads() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> translated = workers.submit(() -> {
                Carrier request = translated();
                CaptionPenStyles.beginCaption(request);
                try {
                    barrier.await(3, TimeUnit.SECONDS);
                    return CaptionPenStyles.getPenId(12);
                } finally {
                    CaptionPenStyles.endCaption(request);
                }
            });
            Future<Integer> original = workers.submit(() -> {
                Carrier request = original();
                CaptionPenStyles.beginCaption(request);
                try {
                    barrier.await(3, TimeUnit.SECONDS);
                    return CaptionPenStyles.getPenId(12);
                } finally {
                    CaptionPenStyles.endCaption(request);
                }
            });
            assertEquals(DEFAULT_PEN, translated.get(5, TimeUnit.SECONDS).intValue());
            assertEquals(12, original.get(5, TimeUnit.SECONDS).intValue());
            Future<Integer> reused = workers.submit(() -> CaptionPenStyles.getPenId(12));
            assertEquals(12, reused.get(5, TimeUnit.SECONDS).intValue());
        } finally {
            workers.shutdownNow();
        }
    }
}
