/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Hides feed posts with a title or text that matches a keyword filter.
 * <p>
 * Old feeds are lists of {@link Link}. Modern feeds are lists of feed elements, and the texts
 * of a post are in child elements that are created before the feed list is processed.
 * The child elements save their texts by link id, and the feed elements are filtered by link id.
 * <p>
 * Reddit opens a modern feed post from a list of {@link Link} that it creates after the feed
 * is processed. A post that the feed shows must stay in that list, or the post fails to open.
 * The links also have texts that the feed does not show, such as the text of NSFW posts.
 * These texts are saved by link id too, and hide the post the next time the feed is processed.
 */
@SuppressWarnings("unused")
public final class KeywordFiltersPatch {
    /**
     * Added to Reddit's feed element base class during patching. The class already has this method.
     */
    public interface FeedElement {
        String getLinkId();
    }

    private record Filters(String rules, List<Pattern> patterns) {
    }

    private static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final String POST_ID_PREFIX = "t3_";

    private static final int TITLE = 0;
    private static final int TRANSLATED_TITLE = 1;
    private static final int PREVIEW_TEXT = 2;
    private static final int TRANSLATED_PREVIEW_TEXT = 3;
    private static final int RICH_TEXT = 4;
    private static final int LINK_TITLE = 5;
    private static final int LINK_TEXT = 6;
    private static final int NUMBER_OF_TEXTS = 7;

    /**
     * Number of posts to keep the texts of. More than the posts of a feed that are kept loaded.
     */
    private static final int MAX_POSTS = 1000;

    private static final class Post {
        final String[] texts = new String[NUMBER_OF_TEXTS];
        /**
         * If a modern feed shows the post.
         */
        boolean shown;
    }

    /**
     * The most recently created feed posts, by link id.
     */
    private static final Map<String, Post> POSTS = new LinkedHashMap<>(MAX_POSTS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Post> eldest) {
            return size() > MAX_POSTS;
        }
    };

    private static volatile Filters filters = new Filters("", List.of());

    private KeywordFiltersPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return The keyword filters, without empty lines and duplicates.
     */
    public static List<String> getRules(String rules) {
        Set<String> lines = new LinkedHashSet<>();
        for (String line : rules.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return new ArrayList<>(lines);
    }

    /**
     * @throws PatternSyntaxException If the keyword filter is not a valid regular expression.
     */
    public static Pattern compile(String rule) {
        return Pattern.compile(rule, PATTERN_FLAGS);
    }

    private static List<Pattern> getPatterns() {
        String rules = Settings.KEYWORD_FILTERS.get();
        Filters current = filters;
        if (current.rules.equals(rules)) {
            return current.patterns;
        }

        List<Pattern> patterns = new ArrayList<>();
        for (String rule : getRules(rules)) {
            try {
                patterns.add(compile(rule));
            } catch (PatternSyntaxException ex) {
                // Possible with imported settings, the settings dialog does not save invalid rules.
                Logger.printException(() -> "Invalid keyword filter: " + rule, ex);
            }
        }
        filters = new Filters(rules, patterns);
        return patterns;
    }

    private static boolean matches(List<Pattern> patterns, @Nullable String text) {
        if (text == null || text.isEmpty()) return false;

        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String getFullId(String linkId) {
        return linkId.startsWith(POST_ID_PREFIX) ? linkId : POST_ID_PREFIX + linkId;
    }

    private static void setText(FeedElement element, int textIndex, @Nullable String text) {
        setText(element.getLinkId(), textIndex, text);
    }

    private static void setText(@Nullable String linkId, int textIndex, @Nullable String text) {
        // Null is not yet known, but an empty text replaces an edited text.
        if (linkId == null || text == null || getPatterns().isEmpty()) return;

        synchronized (POSTS) {
            getPost(getFullId(linkId)).texts[textIndex] = text;
        }
    }

    private static Post getPost(String linkId) {
        Post post = POSTS.get(linkId);
        if (post == null) {
            post = new Post();
            POSTS.put(linkId, post);
        }
        return post;
    }

    /**
     * Injection point. Called when Reddit creates a post model.
     */
    public static void setLink(Link link) {
        try {
            if (getPatterns().isEmpty()) return;

            String linkId = link.getKindWithId();
            setText(linkId, LINK_TITLE, link.getTitle());
            setText(linkId, LINK_TEXT, link.getSelftext());
        } catch (Exception ex) {
            Logger.printException(() -> "setLink failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a post title element or a classic post element.
     */
    public static void setTitle(FeedElement element, @Nullable String title) {
        try {
            setText(element, TITLE, title);
        } catch (Exception ex) {
            Logger.printException(() -> "setTitle failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a post title element or a classic post element.
     */
    public static void setTranslatedTitle(FeedElement element, @Nullable String title) {
        try {
            setText(element, TRANSLATED_TITLE, title);
        } catch (Exception ex) {
            Logger.printException(() -> "setTranslatedTitle failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a preview text element.
     */
    public static void setPreviewText(FeedElement element, @Nullable String text) {
        try {
            setText(element, PREVIEW_TEXT, text);
        } catch (Exception ex) {
            Logger.printException(() -> "setPreviewText failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a preview text element.
     */
    public static void setTranslatedPreviewText(FeedElement element, @Nullable String text) {
        try {
            setText(element, TRANSLATED_PREVIEW_TEXT, text);
        } catch (Exception ex) {
            Logger.printException(() -> "setTranslatedPreviewText failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a rich text element.
     */
    public static void setRichText(FeedElement element, @Nullable String richTextJson) {
        try {
            if (richTextJson == null || getPatterns().isEmpty()) return;

            setText(element, RICH_TEXT, getRichTextVisibleText(richTextJson));
        } catch (Exception ex) {
            Logger.printException(() -> "setRichText failure", ex);
        }
    }

    /**
     * Rich text is a JSON document. Only the visible text is matched, not the JSON keys,
     * the formatting or the media and link data.
     */
    private static String getRichTextVisibleText(String richTextJson) throws JSONException {
        if (richTextJson.isEmpty()) return "";

        StringBuilder text = new StringBuilder();
        appendRichText(new JSONObject(richTextJson).optJSONArray("document"), text);
        return text.toString();
    }

    private static void appendRichText(@Nullable Object node, StringBuilder text) {
        if (node instanceof JSONArray array) {
            for (int i = 0, length = array.length(); i < length; i++) {
                appendRichText(array.opt(i), text);
            }
        } else if (node instanceof JSONObject object) {
            if (object.opt("t") instanceof String string) {
                text.append(string);
            }
            // Child nodes, and the header and rows of tables.
            appendRichText(object.opt("c"), text);
            appendRichText(object.opt("h"), text);
            appendRichText(object.opt("rows"), text);
            // End of a paragraph, heading, line break, list item or table row.
            switch (object.optString("e")) {
                case "par", "h", "br", "li", "tr" -> text.append('\n');
            }
        }
    }

    /**
     * Injection point. Filters the posts of old feeds, and the posts that are opened
     * from a modern feed.
     */
    public static List<?> filterLinks(List<?> links) {
        try {
            // A single post is the post that is opened, such as from a link or a notification.
            if (links.size() <= 1) return links;

            List<Pattern> patterns = getPatterns();
            if (patterns.isEmpty()) return links;

            List<Object> filtered = new ArrayList<>(links.size());
            for (Object item : links) {
                if (item instanceof Link link && !isShown(link.getKindWithId())
                        && (matches(patterns, link.getTitle()) || matches(patterns, link.getSelftext()))) {
                    Logger.printDebug(() -> "Hiding post: " + link.getKindWithId());
                    continue;
                }
                filtered.add(item);
            }
            return filtered.size() == links.size() ? links : filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterLinks failure", ex);
            return links;
        }
    }

    /**
     * Injection point. Filters the posts of modern feeds.
     */
    public static List<?> filterFeedElements(List<?> elements) {
        try {
            List<Pattern> patterns = getPatterns();
            if (patterns.isEmpty()) return elements;

            List<Object> filtered = new ArrayList<>(elements.size());
            for (Object element : elements) {
                if (element instanceof FeedElement feedElement) {
                    String linkId = feedElement.getLinkId();
                    if (linkId != null && isPostFiltered(patterns, getFullId(linkId))) {
                        Logger.printDebug(() -> "Hiding post: " + linkId);
                        continue;
                    }
                    setShown(linkId);
                }
                filtered.add(element);
            }
            return filtered.size() == elements.size() ? elements : filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterFeedElements failure", ex);
            return elements;
        }
    }

    private static boolean isShown(@Nullable String linkId) {
        if (linkId == null) return false;

        synchronized (POSTS) {
            Post post = POSTS.get(getFullId(linkId));
            return post != null && post.shown;
        }
    }

    private static void setShown(@Nullable String linkId) {
        if (linkId == null) return;

        synchronized (POSTS) {
            getPost(getFullId(linkId)).shown = true;
        }
    }

    private static boolean isPostFiltered(List<Pattern> patterns, String linkId) {
        String[] texts;
        synchronized (POSTS) {
            Post post = POSTS.get(linkId);
            if (post == null) return false;
            texts = post.texts.clone();
        }

        for (String text : texts) {
            if (matches(patterns, text)) {
                return true;
            }
        }
        return false;
    }
}
