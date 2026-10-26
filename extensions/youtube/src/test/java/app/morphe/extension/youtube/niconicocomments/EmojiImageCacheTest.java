/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class EmojiImageCacheTest {
    @Test
    public void repeatedLookupsShareOnePendingLoad() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        EmojiImageCache.Entry<String> first = cache.beginLoad("emoji", Set.of("emoji"));
        for (int i = 0; i < 100; i++) assertSame(first, cache.beginLoad("emoji", Set.of("emoji")));
        assertTrue(first.loading);
        assertTrue(cache.complete("emoji", first, "bitmap"));
        assertFalse(first.loading);
        assertEquals("bitmap", first.value);
    }

    @Test
    public void doesNotEvictInFlightLoadsEvenIfNotCurrentlyVisible() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        EmojiImageCache.Entry<String> first = null;
        for (int i = 0; i < EmojiImageCache.MAX_ENTRIES; i++) {
            EmojiImageCache.Entry<String> entry = cache.beginLoad("emoji-" + i, Set.of());
            if (i == 0) first = entry;
        }
        assertNull(cache.beginLoad("extra", Set.of()));
        assertSame(first, cache.get("emoji-0"));
    }

    @Test
    public void oneMoreActiveUrlThanCapacityDoesNotCauseDownloadThrashing() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        Set<String> needed = new HashSet<>();
        for (int i = 0; i <= EmojiImageCache.MAX_ENTRIES; i++) needed.add("emoji-" + i);
        int loads = 0;
        for (int pass = 0; pass < 60; pass++) {
            for (int i = 0; i <= EmojiImageCache.MAX_ENTRIES; i++) {
                String url = "emoji-" + i;
                if (cache.get(url) != null) continue;
                EmojiImageCache.Entry<String> entry = cache.beginLoad(url, needed);
                if (entry != null) {
                    loads++;
                    cache.complete(url, entry, "bitmap");
                }
            }
        }
        assertEquals(EmojiImageCache.MAX_ENTRIES, loads);
    }

    @Test
    public void replacesCompletedImagesAfterTheirCommentsLeaveTheScreen() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        Set<String> needed = new HashSet<>();
        for (int i = 0; i < EmojiImageCache.MAX_ENTRIES; i++) {
            String url = "emoji-" + i;
            needed.add(url);
            EmojiImageCache.Entry<String> entry = cache.beginLoad(url, needed);
            cache.complete(url, entry, "bitmap");
        }
        needed.remove("emoji-0");
        needed.add("extra");
        assertNotNull(cache.beginLoad("extra", needed));
        assertNull(cache.get("emoji-0"));
    }

    @Test
    public void ignoresResultsFromAnOldVideo() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        EmojiImageCache.Entry<String> old = cache.beginLoad("emoji", Set.of());
        cache.clear();
        EmojiImageCache.Entry<String> current = cache.beginLoad("emoji", Set.of());
        assertFalse(cache.complete("emoji", old, "old bitmap"));
        assertSame(current, cache.get("emoji"));
        assertNull(current.value);
    }

    @Test
    public void cachesFailuresInsteadOfRetryingOnEveryFrame() {
        EmojiImageCache<String> cache = new EmojiImageCache<>();
        EmojiImageCache.Entry<String> entry = cache.beginLoad("emoji", Set.of());
        cache.complete("emoji", entry, null);
        assertTrue(entry.failed);
        assertSame(entry, cache.beginLoad("emoji", Set.of("emoji")));
    }

    @Test
    public void workerQueueRejectsWorkPastItsBound() throws Exception {
        ThreadPoolExecutor executor = EmojiImageCache.createExecutor();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < EmojiImageCache.MAX_QUEUED_LOADS; i++) executor.execute(() -> {});
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            assertEquals(EmojiImageCache.MAX_QUEUED_LOADS, executor.getQueue().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
