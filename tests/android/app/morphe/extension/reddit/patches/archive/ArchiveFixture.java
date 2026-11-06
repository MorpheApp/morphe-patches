package app.morphe.extension.reddit.patches.archive;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/** Deterministic transport and inspection, injected ONLY into the instrumentation APK. */
public final class ArchiveFixture {
    public static volatile boolean fail, slow;
    public static volatile boolean holdPreviews = true;
    public static volatile int searches, previews, cancellations;
    public static Object transport() {
        return (ArchiveClient.Transport) (url, token) -> {
            try {
                Uri uri = Uri.parse(url.toString());
                boolean detail = uri.getPath().endsWith("/ids");
                if (detail) previews++; else searches++;
                token.sleep(slow ? 5000 : detail ? 1200 : 180);
                if (detail) while (holdPreviews) token.sleep(50);
                if (fail && !detail) return new ArchiveClient.Response(403, "", Collections.emptyMap());
                JSONArray data = new JSONArray();
                if (detail) {
                    for (String id : uri.getQueryParameter("ids").split(",")) {
                        data.put(row(Integer.parseInt(id, 36) - 1000, "archive_probe")
                                .put("selftext", "Delayed preview text. ".repeat(24))
                                // A masked media preview changes row height without an image download.
                                .put("spoiler", true).put("preview", new JSONObject().put("images", new JSONArray().put(
                                        new JSONObject().put("source", new JSONObject().put("url", "https://preview.redd.it/fixture.png")
                                                .put("width", 640).put("height", 480))))));
                    }
                } else {
                    String author = uri.getQueryParameter("author");
                    long before = uri.getQueryParameter("before") == null ? Long.MAX_VALUE : Long.parseLong(uri.getQueryParameter("before"));
                    long after = uri.getQueryParameter("after") == null ? 0 : Long.parseLong(uri.getQueryParameter("after"));
                    int limit = Integer.parseInt(uri.getQueryParameter("limit"));
                    if (!author.equals("empty_probe")) for (int i = 0; i < 73 && data.length() < limit; i++) {
                        long date = 1790000000L - i * 60;
                        if (date < before && date > after) data.put(row(i, author));
                    }
                }
                return new ArchiveClient.Response(200, new JSONObject().put("data", data).toString(), Collections.emptyMap());
            } catch (java.io.InterruptedIOException cancelled) { cancellations++; throw cancelled; }
            catch (org.json.JSONException error) { throw new IOException(error); }
        };
    }
    private static JSONObject row(int index, String author) throws org.json.JSONException {
        String id = Integer.toString(1000 + index, 36);
        return new JSONObject().put("id", id).put("author", author).put("subreddit", "ArchiveTest")
                .put("created_utc", 1790000000L - index * 60).put("score", 42).put("num_comments", 12)
                .put("title", "Archive fixture row " + index).put("selftext", "A stable text card for native scrolling. ".repeat(index == 1 ? 150 : 1))
                .put("body", ("Comment " + index + ": a stable text card for native scrolling. ").repeat(index == 1 ? 150 : 1))
                .put("post_hint", "self").put("link_id", "t3_" + id);
    }
    public static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    public static ArchiveController controller(String username, boolean comments) {
        try {
            Field field = ArchiveController.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
            for (Object ref : (List<?>) field.get(null)) {
                ArchiveController controller = (ArchiveController) ((WeakReference<?>) ref).get();
                if (controller != null && controller.username.equals(username) && controller.comments == comments) return controller;
            }
            return null;
        } catch (Exception error) { throw new AssertionError(error); }
    }
    public static int count(Object controller) { return ((List<?>) field(controller, "items")).size(); }
    public static void retry(Object controller) { ((ArchiveController) controller).retry(); }
    public static void assertAuthor(Object controller, String author) {
        for (Object item : (List<?>) field(controller, "items")) if (!((ArchiveItem) item).author.equals(author))
            throw new AssertionError("Stale profile result: " + ((ArchiveItem) item).author);
    }
    public static void renderHeader(Object composer) {
        ProfileArchivePatch.androidView(context -> {
            android.widget.TextView text = new android.widget.TextView(context);
            int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
            text.setPadding(pad, pad, pad, pad); text.setTextSize(24);
            text.setText("Native lazy-feed fixture\n\nProfile header\n\nPosts          Comments");
            text.setTag("fixture_header"); return text;
        }, view -> ProfileArchivePatch.unit(), composer);
    }
}
