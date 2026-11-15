/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.preference.PreferenceCategory;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;

/** Native model access is generated at patch time. No reflective app access or public API requests. */
public final class ContentFilterPatch {
    /** Implemented by independently selectable filters registered during class initialization. */
    public interface Filter {
        boolean isEnabled();
        boolean needsFullPostData();
        boolean matches(Post post);
        void addPreferences(Context context, PreferenceCategory category);
        default void observe(Post post) { }
    }

    public interface Node {
        Object[] patch_filterChildren();
        Object[] patch_filterText();
        @Nullable
        String patch_filterCommunity();
        @Nullable
        String patch_filterFlair();
        @Nullable
        String patch_filterId();
    }

    static final String KEYWORDS = "redditplusplus_filter_keywords";
    static final String FLAIRS = "redditplusplus_filter_flairs";
    private static final int MAX_POSTS = 1500;
    private static final int MAX_GRAPH_DEPTH = 8;
    private static final int MAX_RICH_TEXT_DEPTH = 32;
    private static final String POST_ID_PREFIX = "t3_";
    private static final Map<String, Post> POSTS = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Post> entry) {
            return size() > MAX_POSTS;
        }
    };
    private static final List<Filter> FILTERS = new ArrayList<>();
    private static volatile SharedPreferences preferences;

    static {
        registerFilters();
    }

    /** Selected patches inject their registration calls here, without cross-feature references. */
    public static void registerFilters() { }

    public static void registerFilter(Filter filter) {
        if (!FILTERS.contains(filter)) FILTERS.add(filter);
    }

    public static List<Filter> registeredFilters() {
        return Collections.unmodifiableList(FILTERS);
    }

    private ContentFilterPatch() {}

    @SuppressWarnings("deprecation")
    public static SharedPreferences preferences(Context context) {
        SharedPreferences result = preferences;
        if (result != null) return result;
        synchronized (ContentFilterPatch.class) {
            if (preferences != null) return preferences;
            result = context.getApplicationContext().getSharedPreferences("redditplusplus_settings", Context.MODE_PRIVATE);
            SharedPreferences legacy = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = result.edit();
            for (String key : new String[]{KEYWORDS, FLAIRS}) {
                if (!result.contains(key) && legacy.contains(key)) editor.putString(key, legacy.getString(key, ""));
            }
            editor.apply();
            preferences = result;
            return result;
        }
    }

    static String rules(String key) {
        Context context = Utils.getContext();
        return context == null ? "" : preferences(context).getString(key, "");
    }

    static String community(String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return result.startsWith("r/") ? result.substring(2) : result;
    }

    static String flair(String value) {
        return value == null ? "" : android.text.Html.fromHtml(value,
                android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim().replaceAll("\\s+", " ");
    }

    private static String id(@Nullable String value) {
        if (value == null) return "";
        return value.startsWith(POST_ID_PREFIX) ? value.substring(POST_ID_PREFIX.length()) : value;
    }

    public static void rememberLink(Link link) {
        try {
            if (!active()) return;
            Post incoming = fromLink(link);
            String key = id(link.getKindWithId());
            if (key.isEmpty()) return;
            synchronized (POSTS) {
                Post existing = POSTS.get(key);
                if (existing != null) incoming.retainMissingNativeFields(existing);
                POSTS.put(key, incoming);
            }
            observe(incoming);
        } catch (Exception error) {
            Logger.printException(() -> "rememberLink failure", error);
        }
    }

    private static boolean active() {
        for (Filter filter : FILTERS) if (filter.isEnabled()) return true;
        return false;
    }

    public static boolean isSecondaryFetchRemoved(boolean original) {
        if (!original) return false;
        try {
            for (Filter filter : FILTERS) if (filter.needsFullPostData()) return false;
            return true;
        } catch (Exception error) {
            Logger.printException(() -> "isSecondaryFetchRemoved failure", error);
            return original;
        }
    }

    /** Only visible body text is searchable: ignore JSON keys, URLs and media metadata. */
    public static String visibleRichText(@Nullable String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            JSONObject document = new JSONObject(json);
            StringBuilder text = new StringBuilder();
            appendRichText(document.optJSONArray("document"), text, 0);
            return text.toString();
        } catch (Exception error) {
            Logger.printException(() -> "visibleRichText failure", error);
            return "";
        }
    }

    private static void appendRichText(Object value, StringBuilder text, int depth) {
        if (value == null || depth > MAX_RICH_TEXT_DEPTH) return;
        if (value instanceof JSONArray array) {
            for (int index = 0; index < array.length(); index++) appendRichText(array.opt(index), text, depth + 1);
        } else if (value instanceof JSONObject object) {
            Object content = object.opt("t");
            if (content instanceof String string) text.append(string);
            appendRichText(object.opt("c"), text, depth + 1);
            // Table headers/rows are native rtjson child containers too.
            appendRichText(object.opt("h"), text, depth + 1);
            appendRichText(object.opt("rows"), text, depth + 1);
            String element = object.optString("e");
            if (element.equals("par") || element.equals("h") || element.equals("br")
                    || element.equals("li") || element.equals("tr")) text.append('\n');
        }
    }

    private static Post fromLink(Link link) {
        Post result = new Post();
        result.id = id(link.getKindWithId());
        result.title = link.getTitle();
        result.body = link.getBody();
        result.selftext = link.getSelftext();
        result.flairText = link.getLinkFlairText();
        add(result.text, result.title);
        add(result.text, result.body);
        add(result.text, result.selftext);
        result.community = community(link.getSubreddit());
        result.addFlair(result.community, result.flairText);
        return result;
    }

    /** Preserves list identity when nothing matches and never mutates the native immutable list. */
    @Nullable
    public static List<?> filter(@Nullable List<?> original) {
        if (original == null || original.isEmpty()) return original;
        try {
            if (!active()) return original;
            ArrayList<Object> kept = null;
            for (int index = 0; index < original.size(); index++) {
                Object item = original.get(index);
                Post post = new Post();
                read(item, post, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                observe(post);
                boolean blocked = false;
                for (Filter filter : FILTERS) {
                    if (filter.isEnabled() && filter.matches(post)) {
                        blocked = true;
                        Logger.printDebug(() -> "Content filter removed post " + post.id);
                        break;
                    }
                }
                if (blocked && kept == null) kept = new ArrayList<>(original.subList(0, index));
                if (!blocked && kept != null) kept.add(item);
            }
            return kept == null ? original : kept;
        } catch (Exception error) {
            Logger.printException(() -> "filter failure", error);
            return original;
        }
    }

    private static void read(Object value, Post post, Set<Object> seen, int depth) {
        if (value == null || depth > MAX_GRAPH_DEPTH || !seen.add(value)) return;
        if (value instanceof Link link) {
            post.merge(fromLink(link));
        } else if (value instanceof Node node) {
            String nativeCommunity = community(node.patch_filterCommunity());
            if (!nativeCommunity.isEmpty()) post.community = nativeCommunity;
            String nativeFlair = node.patch_filterFlair();
            // Flair models are categorically excluded from keyword input.
            if (nativeFlair == null) {
                Object[] text = node.patch_filterText();
                if (text != null) for (Object part : text) if (part instanceof String s) add(post.text, s);
            }
            // Flair model carries its own community, so identical labels in other subreddits stay visible.
            post.addFlair(nativeCommunity, nativeFlair);
            String key = id(node.patch_filterId());
            if (post.id.isEmpty()) post.id = key;
            if (!key.isEmpty()) synchronized (POSTS) {
                Post cached = POSTS.get(key);
                if (cached != null) post.merge(cached);
            }
            Object[] children = node.patch_filterChildren();
            if (children != null) for (Object child : children) read(child, post, seen, depth + 1);
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) read(item, post, seen, depth + 1);
        }
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    private static void observe(Post post) {
        for (Filter filter : FILTERS) if (filter.isEnabled()) filter.observe(post);
    }

    public static final class Post {
        final Set<String> text = new LinkedHashSet<>();
        final Set<String> flairs = new LinkedHashSet<>();
        String community = "";
        String id = "";
        String title, body, selftext, flairText;
        void retainMissingNativeFields(Post previous) {
            // A newer non-null value replaces old content. In particular, an empty
            // edited body clears the old keyword instead of keeping it indefinitely.
            if (title == null) title = previous.title;
            if (body == null) body = previous.body;
            if (selftext == null) selftext = previous.selftext;
            if (flairText == null) flairText = previous.flairText;
            if (community.isEmpty()) community = previous.community;
            text.clear();
            add(text, title);
            add(text, body);
            add(text, selftext);
            flairs.clear();
            addFlair(community, flairText);
        }
        void addFlair(String owner, String label) {
            String normalized = flair(label);
            if (!owner.isEmpty() && !normalized.isEmpty()) flairs.add(owner + "::" + normalized);
        }
        void merge(Post other) {
            text.addAll(other.text);
            flairs.addAll(other.flairs);
            if (community.isEmpty()) community = other.community;
            if (id.isEmpty()) id = other.id;
        }
    }
}
