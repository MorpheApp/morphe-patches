package app.morphe.extension.reddit.patches.nsfw;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Filters explicit Reddit adult-content flags, never titles, keywords, or image contents.
 *
 * These REST/GraphQL fixtures are a candidate transport boundary. Coverage of the target
 * APK's real schemas must be established before treating this as a complete blocker.
 */
public final class NsfwJsonFilter {
    private static final String[] FLAGS = {"over_18", "over18", "isNsfw", "isNSFW", "nsfw"};
    private static final String[] WRAPPERS = {"data", "node", "post", "subreddit", "user", "content"};
    private static final int MAX_DEPTH = 128;

    private NsfwJsonFilter() {}

    /** null means the root itself describes blocked content. Input is never mutated. */
    public static String filter(String json) throws JSONException {
        JSONTokener parser = new JSONTokener(json);
        Object root = parser.nextValue();
        if (parser.nextClean() != 0) throw new JSONException("Trailing JSON content");
        if (!(root instanceof JSONObject) && !(root instanceof JSONArray)) {
            throw new JSONException("Expected JSON object or array");
        }
        Object filtered = visit(root, 0);
        return filtered == null ? null : filtered.toString();
    }

    private static Object visit(Object value, int depth) throws JSONException {
        if (depth > MAX_DEPTH) throw new JSONException("JSON nesting limit exceeded");
        if (value instanceof JSONArray array) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                Object child = visit(array.get(i), depth + 1);
                if (child != null) result.put(child);
            }
            return result;
        }
        if (!(value instanceof JSONObject object)) return value;
        if (isBlocked(object, 0)) return null;

        JSONObject result = new JSONObject();
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        for (String key : keys) {
            Object child = isPreferences(object) && ("over_18".equals(key) || "search_include_over_18".equals(key))
                    ? false : visit(object.get(key), depth + 1);
            // Preserve nullable object properties; only array entries disappear.
            result.put(key, child == null ? JSONObject.NULL : child);
        }
        return result;
    }

    private static boolean isBlocked(JSONObject object, int depth) throws JSONException {
        if (depth > MAX_DEPTH) throw new JSONException("JSON nesting limit exceeded");
        if (isPreferences(object)) return false;
        if (hasAdultFlag(object) || hasRedgifsLink(object)) return true;
        JSONArray parents = object.optJSONArray("crosspost_parent_list");
        if (parents != null) {
            for (int i = 0; i < parents.length(); i++) {
                JSONObject parent = parents.optJSONObject(i);
                if (parent != null && hasAdultFlag(parent)) return true;
            }
        }
        // Unwrap entity envelopes, but never propagate a list member to its whole page.
        for (String key : WRAPPERS) {
            JSONObject child = object.optJSONObject(key);
            if (child != null && isBlocked(child, depth + 1)) return true;
        }
        return false;
    }

    private static boolean isPreferences(JSONObject object) {
        // AccountPreferencesJsonAdapter in the inspected APK declares both keys.
        // Account viewing preferences are not content labels.
        return object.has("over_18") && object.has("search_include_over_18");
    }

    private static boolean hasRedgifsLink(JSONObject object) {
        if (BlockedNsfwHosts.isRedgifsHost(object.optString("domain", ""))) return true;
        for (String key : new String[]{"url", "url_overridden_by_dest", "urlOverriddenByDest", "mediaPath"}) {
            if (BlockedNsfwHosts.isRedgifsUrl(object.optString(key, ""))) return true;
        }
        return false;
    }

    private static boolean hasAdultFlag(JSONObject object) {
        for (String flag : FLAGS) {
            Object value = object.opt(flag);
            if (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }
}
