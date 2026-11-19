/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4990
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Hides interactive posts, such as games and polls made with the Reddit Developer Platform (Devvit).
 * <p>
 * Modern feeds show such a post with a custom post element, which is created before the feed list
 * is processed. The element saves its link id, and the feed elements are filtered by link id.
 * Old feeds are lists of {@link Link}, which say if they are a custom post.
 * <p>
 * The posts stay in the feeds of their community and in other feeds where they are the content,
 * such as saved posts and search results.
 */
@SuppressWarnings("unused")
public final class HideInteractivePostsPatch {
    /**
     * Added to Reddit's feed element base class during patching. The class already has this method.
     */
    public interface FeedElement {
        String getLinkId();
    }

    /**
     * Added to {@link Link} during patching. The class already has {@link #getKindWithId()}.
     * <p>
     * Feed items are checked for this interface only, not for {@link Link}. The extension compiles
     * against a final {@link Link} stub without this interface, so a check for both looks
     * impossible to the compiler and to code analysis.
     */
    public interface CustomPostLink {
        String getKindWithId();

        boolean patch_isDevPlatformCustomPost();
    }

    private static final String POST_ID_PREFIX = "t3_";

    /**
     * Names of the feed types that keep interactive posts.
     */
    private static final Set<String> KEPT_FEED_TYPES = Set.of(
            "SUBREDDIT",
            "GAMES",
            "ARENA",
            "CLUB",
            "SAVED_POSTS",
            "HISTORY",
            "SUBMITTED_POSTS",
            "SEARCH",
            "QUEUE",
            "PDP"
    );

    /**
     * Number of posts to remember. More than the posts of a feed that are kept loaded.
     */
    private static final int MAX_POSTS = 1000;

    /**
     * Link ids of the most recently created interactive posts, and if a feed shows the post.
     */
    private static final Map<String, Boolean> CUSTOM_POSTS = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_POSTS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_POSTS;
                }
            });

    private HideInteractivePostsPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static String getFullId(String linkId) {
        return linkId.startsWith(POST_ID_PREFIX) ? linkId : POST_ID_PREFIX + linkId;
    }

    /**
     * Injection point. Called when Reddit creates the custom post element of a feed post.
     */
    public static void setCustomPost(FeedElement element) {
        try {
            String linkId = element.getLinkId();
            if (linkId == null) return;

            String fullId = getFullId(linkId);
            CUSTOM_POSTS.putIfAbsent(fullId, Boolean.FALSE);
        } catch (Exception ex) {
            Logger.printException(() -> "setCustomPost failure", ex);
        }
    }

    private static boolean isHiddenIn(@Nullable Object feedType) {
        if (!Settings.HIDE_INTERACTIVE_POSTS.get()) return false;

        // Unknown feeds are hidden in, so that new home feed types are covered.
        return !(feedType instanceof Enum<?> type) || !KEPT_FEED_TYPES.contains(type.name());
    }

    /**
     * Injection point. Filters the posts of modern feeds.
     */
    public static List<?> filterFeedElements(@Nullable Object feedType, List<?> elements) {
        try {
            if (CUSTOM_POSTS.isEmpty()) return elements;

            final boolean hide = isHiddenIn(feedType);
            List<Object> filtered = null;

            for (int i = 0, size = elements.size(); i < size; i++) {
                Object element = elements.get(i);
                boolean remove = false;

                if (element instanceof FeedElement feedElement) {
                    String linkId = feedElement.getLinkId();
                    if (linkId != null) {
                        String fullId = getFullId(linkId);
                        if (CUSTOM_POSTS.containsKey(fullId)) {
                            if (hide) {
                                Logger.printDebug(() -> "Hiding interactive post: " + fullId);
                                remove = true;
                            } else {
                                CUSTOM_POSTS.put(fullId, Boolean.TRUE);
                            }
                        }
                    }
                }

                if (remove) {
                    if (filtered == null) {
                        filtered = new ArrayList<>(elements.subList(0, i));
                    }
                } else if (filtered != null) {
                    filtered.add(element);
                }
            }

            return filtered == null ? elements : filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterFeedElements failure", ex);
            return elements;
        }
    }

    /**
     * Injection point. Filters the posts of old feeds.
     * <p>
     * Reddit also opens a modern feed post from a list of {@link Link}. A post that a modern
     * feed shows must stay in that list, or the post fails to open.
     */
    public static List<?> filterLinks(List<?> links) {
        try {
            // A single post is the post that is opened, such as from a link or a notification.
            if (links.size() <= 1 || !Settings.HIDE_INTERACTIVE_POSTS.get()) return links;

            List<Object> filtered = null;

            for (int i = 0, size = links.size(); i < size; i++) {
                Object item = links.get(i);
                if (item instanceof CustomPostLink link
                        && link.patch_isDevPlatformCustomPost()
                        && !isShown(link.getKindWithId())) {
                    Logger.printDebug(() -> "Hiding interactive post: " + link.getKindWithId());
                    if (filtered == null) {
                        filtered = new ArrayList<>(links.subList(0, i));
                    }
                } else if (filtered != null) {
                    filtered.add(item);
                }
            }

            return filtered == null ? links : filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterLinks failure", ex);
            return links;
        }
    }

    private static boolean isShown(@Nullable String linkId) {
        if (linkId == null) return false;

        return Boolean.TRUE.equals(CUSTOM_POSTS.get(getFullId(linkId)));
    }
}
