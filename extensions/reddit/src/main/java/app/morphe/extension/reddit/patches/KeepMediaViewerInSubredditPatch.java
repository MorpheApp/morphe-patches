/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5155
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.media.MediaContext;
import com.reddit.listing.common.ListingType;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class KeepMediaViewerInSubredditPatch {

    private static final String LISTING_TYPE_USER_SUBMITTED = "USER_SUBMITTED";
    private static final String LISTING_TYPE_SUBMITTED_POSTS = "SUBMITTED_POSTS";

    /**
     * The opened post's subreddit while a request is in flight.
     */
    private static volatile String subredditId;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * Sends the request as a subreddit request when the opened post has one subreddit.
     */
    public static ListingType getListingType(MediaContext context) {
        ListingType listingType = context.getListingType();
        try {
            if (!Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT.get()) {
                subredditId = null;
                return listingType;
            }

            List<String> seeds = context.getSeedSubredditIds();
            if (seeds != null && seeds.size() == 1 && listingType != null && !isUserProfile(listingType)) {
                subredditId = seeds.get(0);
                return ListingType.SUBREDDIT;
            }

            subredditId = null;
            return listingType;
        } catch (Exception ex) {
            Logger.printException(() -> "getListingType failure", ex);
            return listingType;
        }
    }

    private static boolean isUserProfile(ListingType listingType) {
        String name = listingType.name();
        return LISTING_TYPE_USER_SUBMITTED.equals(name) || LISTING_TYPE_SUBMITTED_POSTS.equals(name);
    }

    /**
     * Injection point.
     * Removes posts from other subreddits.
     */
    public static List<?> filterPosts(List<?> posts) {
        try {
            final String id = subredditId;
            if (id == null || posts == null || posts.isEmpty() || !Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT.get()) {
                return posts;
            }

            List<Object> filteredPosts = new ArrayList<>(posts.size());
            for (Object post : posts) {
                if (!(post instanceof Link) || id.equals(((Link) post).getSubredditId())) {
                    filteredPosts.add(post);
                }
            }

            if (filteredPosts.size() == posts.size()) {
                return posts;
            }

            return filteredPosts;
        } catch (Exception ex) {
            Logger.printException(() -> "filterPosts failure", ex);
            return posts;
        }
    }
}
