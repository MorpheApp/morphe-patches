package app.morphe.extension.reddit.patches.archive;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class ArchiveRecoveryTest {
    private static int assertions;
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private interface Failing { void run() throws Exception; }
    private static void fails(Failing action) throws Exception {
        try { action.run(); } catch (IOException expected) { assertions++; return; }
        throw new AssertionError("Expected malformed recovery response to fail");
    }
    private static ArchiveItem item(String id, boolean comments) {
        return new ArchiveItem(id, "true_author", "test", 1700000000, "Original title", "Original **body**", "parent", "", null, comments);
    }
    private static Map<String, ArchiveItem> parse(String row, boolean comments) throws IOException {
        return ArchiveClient.parseRecords("{\"data\":[" + row + "]}", Set.of("abc"), comments);
    }
    public static void main(String[] args) throws Exception {
        parsing(); overlays(); markdown(); store(); originals();
        System.out.println("Archive recovery checks passed (" + assertions + " assertions)");
    }
    private static void originals() {
        RecoveryOriginals originals = new RecoveryOriginals();
        Object nativeModel = new Object() { @Override public int hashCode() { throw new AssertionError("Native hash traversal"); } };
        Object source = new Object(); originals.put(nativeModel, source);
        check(originals.getOrDefault(nativeModel, null) == source, "Native model tracking uses identity without deep hash traversal");
        Object first = new String("same presentation"), second = new String("same presentation");
        originals.put(first, source);
        check(originals.getOrDefault(second, second) == second, "Equal presentations from different rows retain separate originals");
    }
    private static void parsing() throws Exception {
        String row = "{\"id\":\"abc\",\"created_utc\":1700000000,\"author\":\"unknown_before_lookup\",\"title\":\"Original\",\"selftext\":\"Full body\"}";
        ArchiveItem record = parse(row, false).get("t3_abc");
        check(record.author.equals("unknown_before_lookup") && record.text.equals("Full body"), "Author-independent full record");
        check(parse(row.replace("abc", "t3_abc"), false).containsKey("t3_abc"), "Post fullname accepted");
        check(ArchiveClient.recordsUrl(List.of("abc", "def"), false).toString().endsWith("/posts/ids?ids=abc,def"), "Post ID endpoint");
        check(ArchiveClient.recordsUrl(List.of("abc"), true).toString().endsWith("/comments/ids?ids=abc"), "Comment ID endpoint");
        String comment = "{\"id\":\"t1_abc\",\"created_utc\":1700000000,\"body\":\"Comment\",\"link_id\":\"t3_parent\"}";
        record = parse(comment, true).get("t1_abc");
        check(record.comments && record.linkId.equals("parent") && record.author.isEmpty(), "Comment without archived author remains useful");
        check(parse(comment.replace("Comment", "[removed]"), true).get("t1_abc").text.equals("[removed]"), "Unavailable fields retain placeholder for partial recovery");
        fails(() -> parse(row.replace("abc", "different"), false));
        fails(() -> parse(row.replace("abc", "t1_abc"), false));
        fails(() -> parse(comment.replace("t1_abc", "t3_abc"), true));
        fails(() -> parse(comment.replace("t3_parent", "../parent"), true));
        fails(() -> parse(row.replace("\"Original\"", "42"), false));
        fails(() -> parse(row.replace("unknown_before_lookup", "a/b"), false));
        fails(() -> parse(row.replace("1700000000", "-1"), false));
        fails(() -> parse("{}", false));
        fails(() -> parse(row + "," + row, false));
        fails(() -> ArchiveClient.parseRecords("<html>error</html>", Set.of("abc"), false));
        fails(() -> ArchiveClient.recordsUrl(List.of("abc", "abc"), true));
        fails(() -> ArchiveClient.recordsUrl(List.of("a&ids=x"), false));
        fails(() -> ArchiveClient.recordsUrl(List.of(), false));
        check(ArchiveClient.parseRecords("{\"data\":[]}", Set.of("abc"), false).isEmpty(), "Missing record is a miss");
    }
    private static void overlays() {
        for (String placeholder : List.of("[deleted]", "[removed]")) {
            check(ArchiveRecovery.placeholder(placeholder), "Exact placeholder recognized");
            ArchiveRecovery fields = ArchiveRecovery.resolve("t3_abc", null, placeholder, placeholder, placeholder, false, item("abc", false));
            check(fields.changed && fields.title.equals("Original title") && fields.body.equals("Original **body**") && fields.author.equals("true_author"), "All post fields recovered");
            fields = ArchiveRecovery.resolve("t1_abc", "t3_parent", null, placeholder, placeholder, false, item("abc", true));
            check(fields.title == null && fields.body != null && fields.author != null, "Comment fields and parent ID matched");
        }
        for (String readable : List.of("", "hello [deleted]", "[deleted] story", "[Deleted]", " [removed] "))
            check(!ArchiveRecovery.placeholder(readable), "Do not confuse readable text with exact placeholders");
        check(!ArchiveRecovery.eligible("Photo", "", "live_author", false), "An ordinary empty image body does not request recovery");
        check(ArchiveRecovery.eligible("Photo", "", "live_author", true), "Native deletion evidence permits empty body recovery");
        ArchiveRecovery fields = ArchiveRecovery.resolve("t3_abc", null, "Live title", "Live body", "live_author", true, item("abc", false));
        check(!fields.changed, "Live fields win even on a removed post");
        fields = ArchiveRecovery.resolve("t3_abc", null, "Live title", "[deleted]", "live_author", false, item("abc", false));
        check(fields.title == null && fields.author == null && fields.body != null, "Individual field overlay");
        fields = ArchiveRecovery.resolve("t3_abc", null, "Photo", "", "[deleted]", false, item("abc", false));
        check(fields.body == null && fields.author != null, "Deleted account alone does not replace empty body");
        ArchiveItem partial = new ArchiveItem("abc", "", "", 1700000000, "Recovered title", "[removed]", "", "", null, false);
        fields = ArchiveRecovery.resolve("t3_abc", null, "[deleted]", "[removed]", "[deleted]", false, partial);
        check(fields.title != null && fields.body == null && fields.author == null, "Partial archive remains useful");
        check(!ArchiveRecovery.resolve("t3_abc", null, "[deleted]", null, null, true, null).changed, "Miss has no success");
        check(!ArchiveRecovery.resolve("t3_abc", null, "[deleted]", null, null, true, item("xyz", false)).changed, "Reject wrong post ID");
        check(!ArchiveRecovery.resolve("t1_abc", "other", null, "[deleted]", null, true, item("abc", true)).changed, "Reject wrong comment parent");
        check(!ArchiveRecovery.resolve("t1_abc", "../parent", null, "[deleted]", null, true, item("abc", true)).changed, "Reject invalid parent");
        check(!ArchiveRecovery.resolve("t3_abc", null, "[deleted]", null, null, true, item("abc", true)).changed, "Do not cross post/comment namespaces");
        RecoveryTarget metadata = new RecoveryTarget(3, "abc", null, null, null, "u/[deleted]", false);
        check(metadata.author.equals("[deleted]") && metadata.id.equals("t3_abc"), "Normalize displayed author prefix");
        check(!ArchiveRecovery.eligible(metadata.title, metadata.body, "readable", true), "Absent presentation fields cannot request lookup");
        RecoveryTarget rich = new RecoveryTarget(5, "abc", null, null, ArchiveRichText.json("[removed]"), null, false);
        check(ArchiveRecovery.eligible(rich.title, rich.body, rich.author, false), "Native rich-text placeholder detected");
    }
    private static void markdown() throws Exception {
        String markdown = "# Heading\n\nA **bold** and *italic* word, `code`, [link](https://example.org).\n\n> Quote\n\n- One\n- Two\n\n```\ncode block\n```";
        JSONObject json = new JSONObject(ArchiveRichText.json(markdown));
        JSONArray nodes = json.getJSONArray("document");
        check(nodes.length() == 5 && nodes.getJSONObject(0).getString("e").equals("h"), "Native heading and paragraph document");
        check(nodes.getJSONObject(2).getString("e").equals("blockquote") && nodes.getJSONObject(3).getString("e").equals("list"), "Native quotation and list document");
        check(nodes.getJSONObject(4).getString("e").equals("code"), "Native code block document");
        check(ArchiveRichText.preview("Hello **world**").trim().equals("Hello world"), "Preview uses readable text with native length limits");
        check(ArchiveRichText.plain(ArchiveRichText.json("[deleted]")).equals("[deleted]"), "Rich placeholder extraction");
        check(!ArchiveRichText.json("[bad](javascript:alert) ![alt](https://example.org/pic)").contains("\"u\""), "No unsafe links or new archived media");
        check(ArchiveRichText.json("<script>hello</script>").contains("<script>"), "HTML remains text for native rendering");
    }
    private static void await(CountDownLatch latch, String reason) throws Exception { check(latch.await(4, TimeUnit.SECONDS), reason); }
    private static void store() throws Exception {
        ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        AtomicLong clock = new AtomicLong(1000);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        ArchiveRecoveryStore store = new ArchiveRecoveryStore((ids, comments, cancellation) -> {
            calls.incrementAndGet(); seen.addAll(ids); entered.countDown();
            try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new IOException(error); }
            cancellation.check();
            Map<String, ArchiveItem> result = new HashMap<>();
            for (String id : ids) if (!id.equals("missing")) result.put((comments ? "t1_" : "t3_") + id, item(id, comments));
            return result;
        }, worker, Runnable::run, clock::get);
        try {
            CountDownLatch done = new CountDownLatch(3);
            var first = store.subscribe("t3_abc", item -> done.countDown());
            var second = store.subscribe("t3_abc", item -> done.countDown());
            store.subscribe("t3_def", item -> done.countDown());
            await(entered, "Batched lookup starts");
            check(calls.get() == 1 && new HashSet<>(seen).equals(Set.of("abc", "def")), "Deduplicate concurrent feed/discussion observers and batch IDs");
            release.countDown(); await(done, "All subscribers update automatically");
            CountDownLatch cached = new CountDownLatch(1);
            store.subscribe("t3_abc", item -> cached.countDown()); await(cached, "Memory hit delivered");
            check(calls.get() == 1, "Cache does not refetch");
            CountDownLatch miss = new CountDownLatch(1);
            store.subscribe("t3_missing", item -> { check(item == null, "Missing archive delivers no recovered record"); miss.countDown(); });
            await(miss, "Miss completes"); int afterMiss = calls.get();
            CountDownLatch cachedMiss = new CountDownLatch(1);
            store.subscribe("t3_missing", item -> cachedMiss.countDown()); await(cachedMiss, "Miss briefly cached");
            check(calls.get() == afterMiss, "Miss deduplicated across screens");
            CountDownLatch comment = new CountDownLatch(1);
            store.subscribe("t1_abc", item -> { check(item.comments, "Separate comment namespace"); comment.countDown(); }); await(comment, "Comment lookup");
            first.close(); second.close();
            store.clear(); check(store.peek("t3_abc") == null, "Disable clears memory records");
            AtomicInteger stale = new AtomicInteger();
            var departed = store.subscribe("t3_departed", item -> stale.incrementAndGet()); departed.close();
            CountDownLatch barrier = new CountDownLatch(1); worker.schedule(barrier::countDown, 100, TimeUnit.MILLISECONDS); await(barrier, "Queue settles");
            check(stale.get() == 0 && !seen.contains("departed"), "Departed screen cancels queued lookup and callback");
            CountDownLatch watch = new CountDownLatch(1);
            store.subscribe("t3_seed", false, item -> watch.countDown()); store.seed(item("seed", false));
            await(watch, "Profile result publishes to feed metadata observer");
            check(!seen.contains("seed"), "Watch-only observer does not issue unnecessary request");
        } finally { store.clear(); worker.shutdownNow(); }
        ScheduledExecutorService delayedWorker = Executors.newSingleThreadScheduledExecutor();
        Queue<Runnable> delivery = new ConcurrentLinkedQueue<>();
        ArchiveRecoveryStore delayed = new ArchiveRecoveryStore((ids, comments, cancellation) -> Map.of(), delayedWorker, delivery::add, clock::get);
        AtomicInteger late = new AtomicInteger();
        try {
            delayed.seed(item("abc", false)); var subscription = delayed.subscribe("t3_abc", item -> late.incrementAndGet());
            subscription.close(); delivery.remove().run(); check(late.get() == 0, "Recycled row rejects already queued callback");
            delayed.subscribe("t3_abc", item -> late.incrementAndGet()); delayed.clear(); delivery.remove().run();
            check(late.get() == 0, "Disabled setting rejects already queued callback");
            delayed.seed(item("abc", false)); clock.addAndGet(120001); check(delayed.peek("t3_abc") == null, "Short-lived recovery cache expires");
        } finally { delayed.clear(); delayedWorker.shutdownNow(); }
        ScheduledExecutorService toggledWorker = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch pending = new CountDownLatch(1), unwind = new CountDownLatch(1), resumed = new CountDownLatch(1);
        AtomicInteger toggledCalls = new AtomicInteger(), staleCallbacks = new AtomicInteger();
        ArchiveRecoveryStore toggled = new ArchiveRecoveryStore((ids, comments, cancellation) -> {
            if (toggledCalls.incrementAndGet() == 1) {
                pending.countDown();
                try { unwind.await(4, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new IOException(error); }
                cancellation.check();
            }
            return Map.of("t3_abc", item("abc", false));
        }, toggledWorker, Runnable::run, clock::get);
        try {
            toggled.subscribe("t3_abc", item -> staleCallbacks.incrementAndGet());
            await(pending, "Pending request starts before disable");
            toggled.clear();
            toggled.subscribe("t3_abc", item -> resumed.countDown());
            unwind.countDown();
            await(resumed, "Rapid re-enable retries after cancelled request unwinds");
            check(toggledCalls.get() == 2 && staleCallbacks.get() == 0, "New generation loads without stale callback");
        } finally { unwind.countDown(); toggled.clear(); toggledWorker.shutdownNow(); }
    }
}
