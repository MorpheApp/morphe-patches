package app.morphe.extension.reddit.patches.archive;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class FeedRecoveryStoreTest {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(1000);
    private final AtomicInteger calls = new AtomicInteger();
    private ArchiveRecoveryStore store;
    private static ArchiveItem item(String id) {
        return new ArchiveItem(id, "author", "test", 1, "Original title", "Original body", "", "", null, false);
    }
    private void settle() throws Exception { worker.schedule(() -> {}, 100, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS); }
    private void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
    private void create(ArchiveRecoveryStore.Source source) {
        store = new ArchiveRecoveryStore((ids, comments, cancel) -> {
            calls.incrementAndGet(); return source.records(ids, comments, cancel);
        }, worker, Runnable::run, clock::get);
    }
    @After public void close() { if (store != null) store.clear(); worker.shutdownNow(); }

    @Test public void watchRefreshAndRebindNeverRequestUntilTap() throws Exception {
        create((ids, comments, cancel) -> Map.of("t3_post", item("post")));
        for (int i = 0; i < 3; i++) {
            var row = store.subscribe("t3_post", false, record -> {});
            settle(); row.close();
        }
        assertEquals(0, calls.get());
        assertEquals(ArchiveRecoveryStore.Status.IDLE, store.feedStatus("t3_post"));
        store.request("t3_post"); settle();
        assertEquals(1, calls.get()); assertTrue(store.revealed("t3_post"));
        clock.addAndGet(TimeUnit.DAYS.toMillis(1));
        store.subscribe("t3_post", false, record -> assertNotNull(record)).close();
        store.request("t3_post"); settle();
        assertEquals(1, calls.get()); assertNotNull(store.peek("t3_post"));
    }
    @Test public void repeatedTapsAndRowDisposalShareOneRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        create((ids, comments, cancel) -> {
            entered.countDown(); try { finish.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
            cancel.check(); return Map.of("t3_post", item("post"));
        });
        var row = store.subscribe("t3_post", false, record -> {});
        store.request("t3_post"); await(entered);
        store.request("t3_post"); row.close();
        assertEquals(ArchiveRecoveryStore.Status.LOADING, store.feedStatus("t3_post"));
        finish.countDown(); settle();
        assertEquals(1, calls.get()); assertTrue(store.revealed("t3_post"));
    }
    @Test public void profileCacheDoesNotRevealFeedButTapReusesIt() throws Exception {
        create((ids, comments, cancel) -> Map.of("t3_post", item("post")));
        store.subscribe("t3_post", record -> {}).close();
        store.seed(item("post")); settle();
        assertFalse(store.revealed("t3_post"));
        assertEquals(ArchiveRecoveryStore.Status.IDLE, store.feedStatus("t3_post"));
        store.request("t3_post"); settle();
        assertTrue(store.revealed("t3_post")); assertEquals(0, calls.get());
        clock.addAndGet(120001); assertNotNull(store.peek("t3_post"));
    }
    @Test public void failureRequiresExplicitRetryAndMissIsRemembered() throws Exception {
        create((ids, comments, cancel) -> { if (calls.get() == 1) throw new IOException("offline"); return Map.of(); });
        store.request("t3_post"); settle();
        assertEquals(ArchiveRecoveryStore.Status.FAILED, store.feedStatus("t3_post"));
        clock.addAndGet(120001);
        store.subscribe("t3_post", false, record -> {}).close(); settle();
        assertEquals(1, calls.get());
        store.request("t3_post"); settle();
        assertEquals(ArchiveRecoveryStore.Status.MISSING, store.feedStatus("t3_post"));
        clock.addAndGet(120001); store.request("t3_post"); settle();
        assertEquals(2, calls.get());
    }
    @Test public void settingResetRejectsQueuedCallbacksAndClearsReveal() throws Exception {
        Queue<Runnable> deliveries = new ConcurrentLinkedQueue<>();
        store = new ArchiveRecoveryStore((ids, comments, cancel) -> Map.of("t3_post", item("post")), worker, deliveries::add, clock::get);
        AtomicInteger callbacks = new AtomicInteger();
        store.subscribe("t3_post", false, record -> callbacks.incrementAndGet());
        store.request("t3_post"); settle(); assertTrue(store.revealed("t3_post"));
        store.clear(); deliveries.forEach(Runnable::run);
        assertEquals(0, callbacks.get()); assertFalse(store.revealed("t3_post"));
        assertEquals(ArchiveRecoveryStore.Status.IDLE, store.feedStatus("t3_post"));
    }
    @Test public void manualFailureDoesNotDisableAutomaticProfileRetry() throws Exception {
        create((ids, comments, cancel) -> {
            if (calls.get() == 1) throw new IOException("offline");
            return Map.of("t3_post", item("post"));
        });
        store.request("t3_post"); settle();
        clock.addAndGet(10001);
        assertEquals(ArchiveRecoveryStore.Status.FAILED, store.feedStatus("t3_post"));
        var profile = store.subscribe("t3_post", record -> {});
        settle(); profile.close();
        assertEquals(2, calls.get()); assertNotNull(store.peek("t3_post"));
        assertTrue(store.revealed("t3_post"));
    }
    @Test public void boundedCacheEvictionDoesNotGrantAnotherLookup() throws Exception {
        create((ids, comments, cancel) -> { throw new AssertionError("No implicit request"); });
        store.seed(item("post")); store.request("t3_post");
        for (int i = 0; i < 256; i++) store.seed(item("p" + i));
        assertNull(store.peek("t3_post")); assertFalse(store.revealed("t3_post"));
        store.subscribe("t3_post", false, record -> {}).close(); settle();
        assertEquals(0, calls.get());
    }
}
