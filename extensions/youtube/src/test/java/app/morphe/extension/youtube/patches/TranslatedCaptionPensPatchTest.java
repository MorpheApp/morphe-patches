/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class TranslatedCaptionPensPatchTest {
    private static final int DEFAULT_PEN = Integer.MIN_VALUE;

    private static final class Track implements PreferredCaptionLanguagePatch.CaptionTrackInterface {
        final String vss;
        Track(String vss) { this.vss = vss; }
        @Override public String patch_getLanguageCode() { return "tr"; }
        @Override public String patch_getVssId() { return vss; }
    }

    private static final class Request implements TranslatedCaptionPensPatch.CaptionRequestInterface {
        final Object track;
        Request(Object track) { this.track = track; }
        @Override public Object patch_getCaptionTrack() { return track; }
    }

    private static final Request ORIGINAL = new Request(new Track(".tr"));
    private static final Request TRANSLATED = new Request(new Track("t.en.tr"));

    @Test public void translatedTrackUsesDefaultPen() {
        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(7));
        assertEquals(DEFAULT_PEN, TranslatedCaptionPensPatch.getPenId(0));
    }

    @Test public void originalTrackParsedAfterTranslatedKeepsPens() {
        // Cached original tracks are parsed without a new url request.
        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest(ORIGINAL);
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));
    }

    @Test public void otherRequestsKeepPens() {
        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest(new Object());
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));

        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest(new Request("not a track"));
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));

        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest(null);
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));
    }

    @Test public void failedNativeGetterClearsPreviousTranslationState() {
        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest((TranslatedCaptionPensPatch.CaptionRequestInterface) () -> {
            throw new IllegalStateException("getter unavailable");
        });
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));
        TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
        TranslatedCaptionPensPatch.setParsedRequest((TranslatedCaptionPensPatch.CaptionRequestInterface) () -> {
            throw new NoSuchMethodError("native ABI changed");
        });
        assertEquals(7, TranslatedCaptionPensPatch.getPenId(7));
    }

    @Test public void concurrentParsersDoNotShareState() throws Exception {
        CountDownLatch bothSet = new CountDownLatch(2);
        AtomicInteger translatedPen = new AtomicInteger();
        AtomicInteger originalPen = new AtomicInteger();

        Thread translated = new Thread(() -> {
            TranslatedCaptionPensPatch.setParsedRequest(TRANSLATED);
            bothSet.countDown();
            awaitQuietly(bothSet);
            translatedPen.set(TranslatedCaptionPensPatch.getPenId(7));
        });
        Thread original = new Thread(() -> {
            TranslatedCaptionPensPatch.setParsedRequest(ORIGINAL);
            bothSet.countDown();
            awaitQuietly(bothSet);
            originalPen.set(TranslatedCaptionPensPatch.getPenId(7));
        });
        translated.start();
        original.start();
        translated.join();
        original.join();

        assertEquals(DEFAULT_PEN, translatedPen.get());
        assertEquals(7, originalPen.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
}
