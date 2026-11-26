/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4868
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.text.Html;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Hides feed posts with a flair of a community. A flair filter is a line such as
 * {@code gaming::Discussion}, and only hides that flair in that community.
 * <p>
 * Old feeds are lists of {@link Link}. Modern feeds are lists of feed elements, and the flairs
 * of a post are in a child element that is created before the feed list is processed.
 * The home feed has no flairs. After a feed page is shown, Reddit loads the full posts of the page,
 * and these have the flair. The flairs are saved by link id, and the feed elements are filtered by
 * link id. A post of the home feed is hidden the next time the feed is shown.
 */
@SuppressWarnings("unused")
public final class FlairFiltersPatch {
    /**
     * Added to Reddit's feed element base class during patching. The class already has this method.
     */
    public interface FeedElement {
        String getLinkId();
    }

    /**
     * Added to Reddit's flair model during patching.
     */
    public interface Flair {
        @Nullable
        String patch_getText();

        @Nullable
        String patch_getCommunity();
    }

    private record Filters(String rules, Set<String> flairs) {
    }

    private static final String SEPARATOR = "::";
    private static final String COMMUNITY_PREFIX = "r/";
    private static final String POST_ID_PREFIX = "t3_";

    /**
     * Number of posts to keep the flairs of. More than the posts of a feed that are kept loaded.
     */
    private static final int MAX_POSTS = 1000;

    /**
     * Number of seen flairs to remember for choosing flair filters.
     */
    private static final int MAX_SEEN_FLAIRS = 1000;

    /**
     * Flairs of the most recently created feed posts, by link id.
     * Element flairs come from the feed, the link flair from the full post.
     */
    private static final Map<String, PostFlairs> POST_FLAIRS = new LinkedHashMap<>(MAX_POSTS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PostFlairs> eldest) {
            return size() > MAX_POSTS;
        }
    };

    /**
     * Flairs seen since the app started, as flair filters.
     */
    private static final Set<String> SEEN_FLAIRS = new LinkedHashSet<>();

    private static volatile Filters filters = new Filters("", Set.of());

    private static final class PostFlairs {
        @Nullable
        String linkFlair;
        List<String> elementFlairs = List.of();
    }

    private FlairFiltersPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return The flair filters, without empty lines and duplicates.
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
     * @return The flair filter of a flair, such as {@code gaming::Discussion},
     *         or null if the community or the flair is empty.
     */
    @Nullable
    private static String getRule(@Nullable String community, @Nullable String flair) {
        if (community == null || flair == null) return null;

        community = community.trim();
        if (community.regionMatches(true, 0, COMMUNITY_PREFIX, 0, COMMUNITY_PREFIX.length())) {
            community = community.substring(COMMUNITY_PREFIX.length()).trim();
        }
        // Flair texts can have HTML entities such as &amp;
        if (flair.indexOf('&') >= 0) {
            flair = Html.fromHtml(flair, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        flair = flair.trim().replaceAll("\\s+", " ");

        if (community.isEmpty() || flair.isEmpty()) return null;
        return community.toLowerCase(Locale.ROOT) + SEPARATOR + flair;
    }

    /**
     * @return The flair filter in the format used for matching, or null if it is not valid.
     */
    @Nullable
    public static String parseRule(String rule) {
        final int separatorIndex = rule.indexOf(SEPARATOR);
        if (separatorIndex < 0) return null;

        String parsed = getRule(rule.substring(0, separatorIndex),
                rule.substring(separatorIndex + SEPARATOR.length()));
        return parsed == null ? null : parsed.toLowerCase(Locale.ROOT);
    }

    private static Set<String> getFilteredFlairs() {
        String rules = Settings.FLAIR_FILTERS.get();
        Filters current = filters;
        if (current.rules.equals(rules)) {
            return current.flairs;
        }

        Set<String> flairs = new HashSet<>();
        for (String rule : getRules(rules)) {
            String parsed = parseRule(rule);
            if (parsed != null) {
                flairs.add(parsed);
            } else {
                // Possible with imported settings, the settings dialog does not save invalid rules.
                Logger.printDebug(() -> "Invalid flair filter: " + rule);
            }
        }
        filters = new Filters(rules, flairs);
        return flairs;
    }

    private static boolean isFiltered(Set<String> filteredFlairs, @Nullable String rule) {
        return rule != null && filteredFlairs.contains(rule.toLowerCase(Locale.ROOT));
    }

    /**
     * @return If flairs are saved for filtering or for choosing flair filters.
     */
    private static boolean isSavingFlairs() {
        return Settings.FLAIR_FILTERS_REMEMBER_SEEN.get() || !getFilteredFlairs().isEmpty();
    }

    private static void addSeenFlair(@Nullable String rule) {
        if (rule == null || !Settings.FLAIR_FILTERS_REMEMBER_SEEN.get()) return;

        synchronized (SEEN_FLAIRS) {
            if (SEEN_FLAIRS.add(rule) && SEEN_FLAIRS.size() > MAX_SEEN_FLAIRS) {
                SEEN_FLAIRS.remove(SEEN_FLAIRS.iterator().next());
            }
        }
    }

    /**
     * @return The flairs seen since the app started, as flair filters.
     */
    public static List<String> getSeenFlairs() {
        synchronized (SEEN_FLAIRS) {
            return new ArrayList<>(SEEN_FLAIRS);
        }
    }

    private static String getFullId(String linkId) {
        return linkId.startsWith(POST_ID_PREFIX) ? linkId : POST_ID_PREFIX + linkId;
    }

    private static PostFlairs getPostFlairs(String linkId) {
        PostFlairs flairs = POST_FLAIRS.get(linkId);
        if (flairs == null) {
            flairs = new PostFlairs();
            POST_FLAIRS.put(linkId, flairs);
        }
        return flairs;
    }

    /**
     * Injection point. Called when Reddit creates a flairs element of a feed post.
     *
     * @param flairs The {@link Flair} models.
     */
    public static void setFlairs(FeedElement element, @Nullable Object flairs) {
        try {
            if (!(flairs instanceof Iterable<?> iterable) || !isSavingFlairs()) return;

            String linkId = element.getLinkId();
            if (linkId == null) return;

            List<String> rules = new ArrayList<>();
            for (Object item : iterable) {
                if (item instanceof Flair flair) {
                    String rule = getRule(flair.patch_getCommunity(), flair.patch_getText());
                    if (rule != null) {
                        rules.add(rule);
                        addSeenFlair(rule);
                    }
                }
            }

            synchronized (POST_FLAIRS) {
                getPostFlairs(getFullId(linkId)).elementFlairs = rules;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setFlairs failure", ex);
        }
    }

    /**
     * Injection point. Called when Reddit creates a post model.
     * The feed also creates post models without flair data,
     * so a post without flair does not mean the post has no flair.
     */
    public static void setLinkFlair(Link link) {
        try {
            if (!isSavingFlairs()) return;

            String rule = getRule(link.getSubreddit(), link.getLinkFlairText());
            if (rule == null) return;
            addSeenFlair(rule);

            String linkId = link.getKindWithId();
            if (linkId == null) return;

            synchronized (POST_FLAIRS) {
                getPostFlairs(getFullId(linkId)).linkFlair = rule;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setLinkFlair failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * After a feed page is shown, Reddit loads the full posts of the page. This secondary fetch
     * is removed for post previews (text posts), by a feature flag before 2026.45.0.
     * Keep it while there are flair filters, so the flairs of home feed posts are known.
     */
    public static boolean isPostPreviewSecondaryFetchRemoved(boolean original) {
        try {
            return original && getFilteredFlairs().isEmpty();
        } catch (Exception ex) {
            Logger.printException(() -> "isPostPreviewSecondaryFetchRemoved failure", ex);
            return original;
        }
    }

    /**
     * Injection point. Filters the posts of old feeds.
     */
    public static List<?> filterLinks(List<?> links) {
        try {
            Set<String> filteredFlairs = getFilteredFlairs();
            if (filteredFlairs.isEmpty()) return links;

            List<Object> filtered = new ArrayList<>(links.size());
            for (Object item : links) {
                if (item instanceof Link link && isFiltered(filteredFlairs,
                        getRule(link.getSubreddit(), link.getLinkFlairText()))) {
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
            Set<String> filteredFlairs = getFilteredFlairs();
            if (filteredFlairs.isEmpty()) return elements;

            List<Object> filtered = new ArrayList<>(elements.size());
            for (Object element : elements) {
                if (element instanceof FeedElement feedElement) {
                    String linkId = feedElement.getLinkId();
                    if (linkId != null && isPostFiltered(filteredFlairs, getFullId(linkId))) {
                        Logger.printDebug(() -> "Hiding post: " + linkId);
                        continue;
                    }
                }
                filtered.add(element);
            }
            return filtered.size() == elements.size() ? elements : filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterFeedElements failure", ex);
            return elements;
        }
    }

    private static boolean isPostFiltered(Set<String> filteredFlairs, String linkId) {
        String linkFlair;
        List<String> elementFlairs;
        synchronized (POST_FLAIRS) {
            PostFlairs flairs = POST_FLAIRS.get(linkId);
            if (flairs == null) return false;
            linkFlair = flairs.linkFlair;
            elementFlairs = flairs.elementFlairs;
        }

        if (isFiltered(filteredFlairs, linkFlair)) return true;
        for (String flair : elementFlairs) {
            if (isFiltered(filteredFlairs, flair)) {
                return true;
            }
        }
        return false;
    }
}
