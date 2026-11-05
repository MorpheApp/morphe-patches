package app.morphe.extension.reddit.patches.archive;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class ArchiveCoreTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        parsingAndLinks();
        previewMetadata();
        pagination();
        cancellationAndRetry();
        rateHeaders();
        System.out.println("Archive core checks passed (" + assertions + " assertions)");
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private interface Failing { void run() throws Exception; }
    private static IOException fails(Failing action) throws Exception {
        try { action.run(); } catch (IOException expected) { assertions++; return expected; }
        throw new AssertionError("Expected IOException");
    }
    private static void parsingAndLinks() throws Exception {
        String row = "{\"id\":\"t1_abc\",\"author\":\"Some_User\",\"created_utc\":1700000000,"
                + "\"body\":\"line 1\\nline 2 & <plain text>\",\"link_id\":\"t3_def\",\"permalink\":\"https://evil.invalid/\"}";
        ArchiveItem item = ArchiveClient.parse("{\"data\":[" + row + "]}", "some_user", true, 25).get(0);
        check(item.text.equals("line 1\nline 2 & <plain text>"), "Preserve text without rendering HTML");
        check(item.score == null && item.subreddit.isEmpty(), "Optional metadata may be absent");
        check(item.discussionUrl().equals("https://www.reddit.com/comments/def/_/abc/"), "Build trusted comment URL from IDs");
        check(ArchiveClient.parse("{\"data\":[]}", "some_user", false, 25).isEmpty(), "Empty archive");
        fails(() -> ArchiveClient.parse("<html>bad gateway</html>", "some_user", true, 25));
        fails(() -> ArchiveClient.parse("{\"error\":\"timeout\"}", "some_user", true, 25));
        fails(() -> ArchiveClient.parse("{\"data\":[" + row + "]}", "another_user", true, 25));
        fails(() -> ArchiveClient.parse("{\"data\":[" + row.replace("t3_def", "../../outside") + "]}", "some_user", true, 25));
        fails(() -> ArchiveClient.parse("{\"data\":[{}]}", "some_user", true, 25));
        fails(() -> ArchiveClient.searchUrl("test&author=other", false, null, null, 25));
        fails(() -> ArchiveClient.searchUrl("u/name", false, null, null, 25));
        String posts = ArchiveClient.searchUrl("some_user", false, 100L, null, 25).toString();
        String comments = ArchiveClient.searchUrl("some_user", true, 100L, 98L, 100).toString();
        check(posts.contains("/posts/search?") && posts.contains("&before=100"), "Posts endpoint and cursor");
        check(comments.contains("/comments/search?") && comments.contains("&after=98"), "Comments endpoint and boundary query");
    }
    private static ArchiveItem item(int id, long timestamp) {
        return new ArchiveItem(Integer.toString(id, 36), "some_user", "test", timestamp,
                "Title", "Body", "link", "", null, false);
    }
    private static void previewMetadata() throws Exception {
        String image = "{\"url\":\"https://preview.redd.it/photo.jpg?width=1080&amp;auto=webp\",\"width\":1080,\"height\":720}";
        String row = "{\"id\":\"abc\",\"author\":\"some_user\",\"title\":\"A photo\",\"num_comments\":42,"
                + "\"link_flair_text\":\"Photo\",\"over_18\":true,\"spoiler\":true,\"preview\":{\"images\":[{\"source\":" + image
                + ",\"resolutions\":[{\"url\":\"https://preview.redd.it/small.jpg\",\"width\":320,\"height\":200}," + image + "]}]}}";
        ArchivePostDetails details = ArchiveClient.parseDetails("{\"data\":[" + row + "]}", Set.of("abc")).get("abc");
        check(details.title.equals("A photo") && details.commentCount == 42 && details.flair.equals("Photo"), "Preserve optional card metadata");
        check(details.nsfw && details.spoiler, "Content masking flags survive enrichment");
        check(details.preview.width == 1080 && details.preview.height == 720, "Choose display-sized preview");
        check(details.preview.url.contains("&auto=") && !details.preview.url.contains("&amp;"), "Decode escaped preview query");
        for (String bad : List.of("http://i.redd.it/a.jpg", "https://i.redd.it.evil.test/a.jpg", "https://evil.test/a.jpg",
                "https://i.redd.it@evil.test/a.jpg", "https://name@i.redd.it/a.jpg", "https://i.redd.it:443/a.jpg",
                "file:///tmp/image.png", "data:image/png;base64,AAAA", "https://127.0.0.1/a.jpg", "https://preview.redd.it/a.jpg#fragment")) {
            check(ArchivePostDetails.imageUrl(bad) == null, "Reject unsafe image URL: " + bad);
        }
        check(ArchivePostDetails.imageUrl("https://external-preview.redd.it/a.webp") != null, "Reddit external preview CDN allowed");
        check(ArchivePostDetails.imageUrl("https://i.redd.it/" + "a".repeat(4100)) == null, "Bound URL length");
        fails(() -> ArchiveClient.detailsUrl(List.of()));
        fails(() -> ArchiveClient.detailsUrl(List.of("a&ids=other")));
        fails(() -> ArchiveClient.detailsUrl(java.util.Collections.nCopies(9, "abc")));
        check(ArchiveClient.detailsUrl(List.of("abc", "def")).toString().endsWith("/posts/ids?ids=abc,def"), "Batch full records without unsupported field projection");
        fails(() -> ArchiveClient.parseDetails("{\"data\":[" + row + "]}", Set.of("def")));
        fails(() -> ArchiveClient.parseDetails("{\"data\":[" + row + "," + row + "]}", Set.of("abc", "def")));
        fails(() -> ArchiveClient.parseDetails("{\"data\":[{}]}", Set.of("abc")));
        ArchivePostDetails missing = ArchiveClient.parseDetails("{\"data\":[{\"id\":\"abc\"}]}", Set.of("abc")).get("abc");
        check(missing.preview == null && missing.commentCount == null && missing.title.isEmpty(), "Missing enrichment degrades to text");
        String malformed = row.replace("1080", "-1").replace("320", "999999");
        check(ArchiveClient.parseDetails("{\"data\":[" + malformed + "]}", Set.of("abc")).get("abc").preview == null, "Invalid dimensions do not break a row");
        String gallery = "{\"id\":\"abc\",\"gallery_data\":{\"items\":[{\"media_id\":\"second\"},{\"media_id\":\"first\"}]},"
                + "\"media_metadata\":{\"first\":{\"s\":{\"u\":\"https://i.redd.it/first.jpg\",\"x\":800,\"y\":600}},"
                + "\"second\":{\"s\":{\"u\":\"https://i.redd.it/second.jpg\",\"x\":600,\"y\":800}}}}";
        ArchivePostDetails album = ArchiveClient.parseDetails("{\"data\":[" + gallery + "]}", Set.of("abc")).get("abc");
        check(album.galleryCount == 2 && album.preview.url.endsWith("second.jpg"), "Gallery cover follows gallery order, not object order");
        String video = "{\"id\":\"abc\",\"is_video\":true,\"thumbnail\":\"https://i.redd.it/poster.jpg\",\"thumbnail_width\":140,\"thumbnail_height\":100}";
        ArchivePostDetails poster = ArchiveClient.parseDetails("{\"data\":[" + video + "]}", Set.of("abc")).get("abc");
        check(poster.video && poster.preview.url.endsWith("poster.jpg"), "Video uses poster, not playback stream");
        String simple = "{\"id\":\"abc\",\"author\":\"some_user\",\"created_utc\":1700000000,\"num_comments\":7,\"over_18\":true,\"spoiler\":true,\"link_flair_text\":\"News\"}";
        ArchiveItem card = ArchiveClient.parse("{\"data\":[" + simple + "]}", "some_user", false, 25).get(0);
        check(card.commentCount == 7 && card.nsfw && card.spoiler && card.flair.equals("News"), "Lightweight paging includes flags before preview requests");
        check(ArchiveDisplay.count(null, "Vote").equals("Vote") && ArchiveDisplay.count(0L, "Vote").equals("0"), "Missing count is distinct from zero");
        check(ArchiveDisplay.count(-12L, "Vote").equals("-12"), "Negative archived scores remain negative");
        check(ArchiveDisplay.count(1000L, "Vote").equals("1k"), "Compact whole thousands");
        check(ArchiveDisplay.age(100, 99).equals("now") && ArchiveDisplay.age(100, 160).equals("1m"), "Relative time boundaries");
        check(ArchiveDisplay.age(100, 3700).equals("1h") && ArchiveDisplay.age(100, 86500).equals("1d"), "Relative hours and days");
        check(ArchiveDisplay.age(100, 31536100).equals("1y"), "Relative archive years");
        AtomicInteger calls = new AtomicInteger();
        ArchiveClient fake = new ArchiveClient((url, cancel) -> {
            calls.incrementAndGet(); return new ArchiveClient.Response(200, "{\"data\":[" + row + "]}", Map.of());
        });
        check(fake.details(List.of("abc"), new ArchiveClient.Cancellation()).get("abc").preview != null && calls.get() == 1, "Enrichment uses shared transport");
        ArchiveClient.Cancellation cancelled = new ArchiveClient.Cancellation(); cancelled.cancel();
        check(fails(() -> fake.details(List.of("abc"), cancelled)) instanceof InterruptedIOException && calls.get() == 1, "Cancelled enrichment never reaches transport");
    }
    private static ArchivePager.Source source(List<ArchiveItem> data, AtomicInteger calls) {
        return (user, comments, before, after, limit, cancellation) -> {
            cancellation.check(); calls.incrementAndGet();
            check(user.equals("some_user") && !comments, "Keep username and tab scoped to this pager");
            return data.stream().filter(row -> (before == null || row.createdUtc < before)
                            && (after == null || row.createdUtc > after))
                    .sorted(Comparator.comparingLong((ArchiveItem row) -> row.createdUtc).reversed())
                    .limit(limit).toList();
        };
    }
    private static void pagination() throws Exception {
        List<ArchiveItem> data = new ArrayList<>();
        for (int i = 0; i < 20; i++) data.add(item(i, 2000 - i));
        for (int i = 20; i < 60; i++) data.add(item(i, 1980));
        for (int i = 60; i < 80; i++) data.add(item(i, 2039 - i));
        AtomicInteger calls = new AtomicInteger();
        ArchivePager pager = new ArchivePager();
        Set<String> ids = new HashSet<>();
        boolean more = true;
        long last = Long.MAX_VALUE;
        int pages = 0;
        while (more) {
            check(++pages < 10, "Pagination must terminate");
            ArchivePager.Page page = pager.next(source(data, calls), "some_user", false, new ArchiveClient.Cancellation());
            check(page.items.size() <= 25, "Display at most 25 records per page");
            for (ArchiveItem row : page.items) {
                check(ids.add(row.id), "No duplicate IDs at timestamp boundaries");
                check(row.createdUtc <= last, "Newest first across pages");
                last = row.createdUtc;
            }
            more = page.more;
        }
        check(ids.size() == 80 && calls.get() == 3, "No gaps, including 40 records at the boundary second");

        List<ArchiveItem> dense = new ArrayList<>();
        for (int i = 0; i < 120; i++) dense.add(item(i, 500));
        ArchivePager capped = new ArchivePager();
        ArchivePager.Page cap;
        int returned = 0;
        do {
            cap = capped.next(source(dense, new AtomicInteger()), "some_user", false, new ArchiveClient.Cancellation());
            returned += cap.items.size();
        } while (cap.more);
        check(returned == 100 && cap.notice.contains("timestamp limit"), "Explicitly stop at an unresolved timestamp cap");

        ArchivePager original = new ArchivePager();
        AtomicInteger failCalls = new AtomicInteger();
        fails(() -> original.copy().next((user, comments, before, after, limit, cancel) -> {
            if (failCalls.incrementAndGet() == 2) throw new IOException("Boundary request failed");
            return data.subList(0, 25);
        }, "some_user", false, new ArchiveClient.Cancellation()));
        ArchivePager.Page retried = original.copy().next(source(data, new AtomicInteger()), "some_user", false, new ArchiveClient.Cancellation());
        check(retried.items.get(0).id.equals(data.get(0).id), "Failed request does not commit a cursor");
        ArchivePager.Page empty = new ArchivePager().next(source(List.of(), new AtomicInteger()), "some_user", false, new ArchiveClient.Cancellation());
        check(empty.items.isEmpty() && !empty.more, "Empty history terminates");
    }

    private static void cancellationAndRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ArchiveClient.Cancellation cancelled = new ArchiveClient.Cancellation();
        cancelled.cancel();
        ArchiveClient client = new ArchiveClient((url, cancel) -> {
            calls.incrementAndGet(); return new ArchiveClient.Response(200, "{\"data\":[]}", Map.of());
        });
        check(fails(() -> client.search("some_user", false, null, null, 25, cancelled)) instanceof InterruptedIOException,
                "Cancellation has its own completion path");
        check(calls.get() == 0, "Cancelled requests never reach transport");
        ArchiveClient retry = new ArchiveClient((url, cancel) -> new ArchiveClient.Response(
                calls.incrementAndGet() == 1 ? 503 : 200, "{\"data\":[]}", Map.of()));
        check(retry.search("some_user", false, null, null, 25, new ArchiveClient.Cancellation()).isEmpty()
                && calls.get() == 2, "One bounded retry on transient server errors");
        calls.set(0);
        ArchiveClient timeout = new ArchiveClient((url, cancel) -> { calls.incrementAndGet(); throw new SocketTimeoutException(); });
        IOException failure = fails(() -> timeout.search("some_user", false, null, null, 25, new ArchiveClient.Cancellation()));
        check(!(failure instanceof InterruptedIOException) && calls.get() == 2,
                "Timeouts produce retryable UI errors, not silent cancellation");
    }

    private static void rateHeaders() throws Exception {
        long now = 1700000000000L;
        check(ArchiveClient.retryDelayMillis(new ArchiveClient.Response(429, "", Map.of("x-ratelimit-reset", List.of("2.5"))), now) == 2500,
                "Reset header uses seconds");
        check(ArchiveClient.retryDelayMillis(new ArchiveClient.Response(429, "", Map.of("X-RateLimit-Reset-At", List.of("1700000005"))), now) == 5000,
                "Reset-at is an epoch timestamp");
        check(ArchiveClient.retryDelayMillis(new ArchiveClient.Response(429, "", Map.of("Retry-After", List.of("Tue, 14 Nov 2023 22:13:25 GMT"))), now) == 5000,
                "Retry-After date is respected");
        check(ArchiveClient.retryDelayMillis(new ArchiveClient.Response(429, "", Map.of("Retry-After", List.of("NaN"))), now) == 30000,
                "Invalid rate headers use a conservative fallback");
        AtomicInteger calls = new AtomicInteger();
        ArchiveClient limited = new ArchiveClient((url, cancel) -> {
            calls.incrementAndGet(); return new ArchiveClient.Response(429, "", Map.of("Retry-After", List.of("60")));
        });
        fails(() -> limited.search("some_user", false, null, null, 25, new ArchiveClient.Cancellation()));
        fails(() -> limited.search("some_user", true, null, null, 25, new ArchiveClient.Cancellation()));
        check(calls.get() == 1, "Rate limit blocks subsequent tabs without hammering the provider");
    }
}
