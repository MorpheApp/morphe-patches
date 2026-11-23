/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5155
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.media.MediaContext;
import com.reddit.listing.common.ListingType;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class KeepMediaViewerInSubredditPatch {

    /**
     * Subreddit id of the post opened in the media viewer, or null if posts are not filtered.
     * The request method is suspended between sending the request and creating the Listing,
     * and the media context is not available after it resumes.
     */
    @Nullable
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
     * Requests posts from the subreddit endpoint when the opened post has a subreddit.
     */
    @Nullable
    public static ListingType getListingType(MediaContext context) {
        ListingType listingType = context.getListingType();
        if (!Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT.get()) {
            return listingType;
        }

        try {
            String id = null;
            // On a user profile, keep swiping through the posts of the user.
            if (listingType != null && listingType != ListingType.USER_SUBMITTED
                    && listingType != ListingType.SUBMITTED_POSTS) {
                List<String> seedSubredditIds = context.getSeedSubredditIds();
                if (seedSubredditIds != null && seedSubredditIds.size() == 1) {
                    id = seedSubredditIds.get(0);
                }
            }

            subredditId = id;
            return id == null ? listingType : ListingType.SUBREDDIT;
        } catch (Exception ex) {
            Logger.printException(() -> "getListingType failure", ex);
            return listingType;
        }
    }

    /**
     * Injection point.
     * Removes posts from other subreddits.
     */
    @Nullable
    public static List<?> filterPosts(@Nullable List<?> posts) {
        if (!Settings.KEEP_MEDIA_VIEWER_IN_SUBREDDIT.get()) {
            return posts;
        }

        try {
            final String id = subredditId;
            if (id == null || posts == null) {
                return posts;
            }

            List<Object> filteredPosts = new ArrayList<>(posts.size());
            for (Object post : posts) {
                if (!(post instanceof Link link) || id.equals(link.getSubredditId())) {
                    filteredPosts.add(post);
                }
            }

            return filteredPosts.size() == posts.size() ? posts : filteredPosts;
        } catch (Exception ex) {
            Logger.printException(() -> "filterPosts failure", ex);
            return posts;
        }
    }
}
