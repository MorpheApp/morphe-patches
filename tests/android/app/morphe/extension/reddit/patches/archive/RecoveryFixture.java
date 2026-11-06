package app.morphe.extension.reddit.patches.archive;

import android.net.Uri;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Pinned native-model and asynchronous Compose fixture. Never packaged in a release. */
public final class RecoveryFixture {
    public static volatile int requests, cancellations, assertions;
    public static volatile int failuresReceived;
    public static volatile boolean hold = true, fail, shown = true;
    private static Object post, body, header, profile;
    public static volatile Object projectedPost, projectedBody, projectedHeader;
    public static String[] hookOwners() { throw new UnsupportedOperationException(); }
    private static final String TEXT = "Restored **bold** text with *emphasis* and `code`.\n\n> Quoted paragraph\n\n- First\n- Second";
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    public static Object transport() {
        return (ArchiveClient.Transport) (url, token) -> {
            requests++;
            try {
                while (hold) token.sleep(25);
                token.sleep(100);
                if (fail) return new ArchiveClient.Response(429, "", Map.of("Retry-After", List.of("60")));
                Uri uri = Uri.parse(url.toString());
                boolean comments = uri.getPath().contains("comments");
                JSONArray data = new JSONArray();
                for (String id : uri.getQueryParameter("ids").split(",")) {
                    if (id.equals("missing")) continue;
                    data.put(new JSONObject().put("id", id).put("author", "original_author")
                        .put("created_utc", 1700000000).put("subreddit", "ArchiveTest")
                        .put("title", "Restored post title").put(comments ? "body" : "selftext", TEXT).put("link_id", "t3_post"));
                }
                return new ArchiveClient.Response(200, new JSONObject().put("data", data).toString(), Map.of());
            } catch (java.io.InterruptedIOException cancelled) { cancellations++; throw cancelled; }
            catch (JSONException error) { throw new java.io.IOException(error); }
        };
    }
    public static void prepare() throws Exception {
        RecoveryPatch.STORE.clear();
        if (app.morphe.test.ArchiveProbe.recoveryFailure) for (String id : List.of("t3_post", "t1_comment", "t1_profile"))
            RecoveryPatch.STORE.subscribe(id, false, record -> { if (record == null) failuresReceived++; });
        for (String name : hookOwners()) { Class.forName(name); check(true, "Android verifies patched renderer " + name); }
        Object author = model("vb40", 9); set(author, "b", "[deleted]"); set(author, "c", "[deleted]");
        Object metadata = model("jc40", 23); set(metadata, "a", author); set(metadata, "c", "2h"); set(metadata, "m", "t3_post"); set(metadata, "n", "post");
        Object title = construct("af40", "[deleted]");
        Object text = construct("xa40", ArchiveRichText.json("[deleted]"), null, "[deleted]");
        Object content = model("n940", 5); set(content, "c", text);
        post = model("mf40", 22); set(post, "a", metadata); set(post, "c", title); set(post, "d", content);
        body = model("ag9", 14); set(body, "a", "[removed]"); set(body, "b", ArchiveRichText.json("[removed]"));
        set(body, "e", 3); set(body, "i", "t1_comment"); set(body, "j", true); set(body, "k", "t3_post"); set(body, "l", "comment");
        header = model("jw9", 25); set(header, "a", "t1_comment"); set(header, "b", "2h"); set(header, "c", "[deleted]"); set(header, "e", "[removed]");
        Object profileMeta = construct("z1a", text("r/ArchiveTest"), "2h", "42", false);
        profile = construct("tf50", text("Live discussion title"), profileMeta, text("[deleted]"), false,
                Class.forName("ch20").getMethod("T", Class.forName("zau")).invoke(null, new Object[]{null}));
        check(RecoveryPatch.describe(post).id.equals("t3_post"), "Direct post ID bridge");
        check(RecoveryPatch.describe(body).id.equals("t1_comment"), "Nested comment ID bridge");
        check(RecoveryPatch.project(post, RecoveryPatch.describe(post)) == post, "Loading keeps native model");
        check(RecoveryPatch.project(body, RecoveryPatch.describe(body)) == body, "Loading keeps nested comment");
        check(((List<?>) RecoveryPatch.richElements(ArchiveRichText.json(TEXT))).size() >= 3, "Archive Markdown parses through Reddit's native rich-text parser");
    }
    private static List<?> rows;
    public static List<?> sections() {
        if (!shown) return List.of();
        try {
            if (rows == null) rows = List.of(construct("nw9", profile, "t3_post", "t1_profile"));
            return rows;
        } catch (Exception error) { throw new AssertionError(error); }
    }
    public static void observe(Object composer) {
        if (!shown) return;
        projectedPost = RecoveryPatch.recover(post, composer);
        projectedBody = RecoveryPatch.recover(body, composer);
        projectedHeader = RecoveryPatch.recover(header, composer);
    }
    public static boolean ready() {
        return projectedPost != null && projectedPost != post && projectedBody != null && projectedBody != body
                && projectedHeader != null && projectedHeader != header;
    }
    public static void verify() throws Exception {
        Object recovered = projectedPost;
        check(get(get(recovered, "c"), "a").equals("Restored post title"), "Post title restored");
        Object meta = get(recovered, "a");
        check(get(get(meta, "a"), "b").equals("original_author"), "Post username restored");
        check(get(meta, "c").equals("2h · Undeleted"), "Single post metadata tag");
        check(get(get(get(recovered, "d"), "c"), "c").equals(TEXT), "Post full text restored");
        check(get(get(get(recovered, "d"), "c"), "a").equals(ArchiveRichText.json(TEXT)), "Stale post rich text replaced");
        unchanged(post, recovered, Set.of("a", "c", "d", "l"));
        check(get(projectedBody, "a").equals(TEXT) && get(projectedBody, "b").equals(ArchiveRichText.json(TEXT)), "Comment text and rich input restored");
        check(((List<?>) get(projectedBody, "d")).size() >= 3, "Comment native parsed rich elements restored");
        check(get(projectedBody, "e").equals(3) && get(projectedBody, "i").equals("t1_comment"), "Comment depth and ID retained");
        check(get(projectedBody, "j").equals(false), "Removed-text presentation condition cleared");
        unchanged(body, projectedBody, Set.of("a", "b", "d", "j", "n"));
        check(get(projectedHeader, "c").equals("original_author") && get(projectedHeader, "b").equals("2h · Undeleted"), "Comment author and one metadata tag");
        unchanged(header, projectedHeader, Set.of("b", "c", "e"));
        check(get(body, "a").equals("[removed]") && get(get(post, "c"), "a").equals("[deleted]"), "Original native models remain untouched");
        // Feed and profile presentations consume the same recovered records.
        Object feed = model("x140", 13); set(feed, "e", "t3_post"); set(feed, "i", "[deleted]"); set(feed, "j", "[deleted]"); set(feed, "k", true);
        Object projected = RecoveryPatch.project(feed, RecoveryPatch.describe(feed));
        check(get(projected, "i").equals("Restored post title") && get(projected, "k").equals(false), "Feed title wins over stale translated placeholder");
        unchanged(feed, projected, Set.of("i", "j", "k"));
        set(feed, "i", "Live readable title");
        check(get(RecoveryPatch.project(feed, RecoveryPatch.describe(feed)), "i").equals("Live readable title"), "Live feed title wins");
        Object missing = model("x140", 13); set(missing, "e", "t3_missing"); set(missing, "i", "[deleted]");
        check(RecoveryPatch.project(missing, RecoveryPatch.describe(missing)) == missing, "No success overlay without a matching archive");
        Object before = RecoveryPatch.project(body, RecoveryPatch.describe(body));
        check(before.equals(projectedBody), "Collapse/expand projection is stable and does not duplicate tags");
    }
    public static void verifyDisabled() { check(projectedPost == post && projectedBody == body && projectedHeader == header, "Setting disablement automatically restores native input"); }
    private static void unchanged(Object original, Object projected, Set<String> allowed) throws Exception {
        for (Field field : original.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && !allowed.contains(field.getName())) {
            field.setAccessible(true); check(Objects.equals(field.get(original), field.get(projected)), "Preserve native " + original.getClass().getName() + "." + field.getName());
        }
    }
    private static Object model(String name, int count) throws Exception {
        for (Constructor<?> ctor : Class.forName(name).getConstructors()) if (ctor.getParameterCount() == count) {
            Object[] values = new Object[count]; Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < count; i++) {
                if (types[i] == String.class) values[i] = "";
                else if (types[i] == boolean.class) values[i] = false;
                else if (types[i] == int.class) values[i] = 0;
                else if (List.class.isAssignableFrom(types[i])) values[i] = types[i].getName().equals("java.util.List") ? List.of() : ProfileArchivePatch.immutableList(List.of());
                else if (types[i].isEnum()) values[i] = types[i].getEnumConstants()[0];
            }
            return ctor.newInstance(values);
        }
        throw new AssertionError("Missing fixture constructor " + name + " " + count);
    }
    private static Object construct(String name, Object... values) throws Exception {
        for (Constructor<?> ctor : Class.forName(name).getConstructors()) if (ctor.getParameterCount() == values.length) return ctor.newInstance(values);
        throw new AssertionError("Missing fixture constructor " + name);
    }
    private static Object text(String value) throws Exception { return Class.forName("ij20").getMethod("e0", String.class).invoke(null, value); }
    private static void set(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    private static Object get(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
}
