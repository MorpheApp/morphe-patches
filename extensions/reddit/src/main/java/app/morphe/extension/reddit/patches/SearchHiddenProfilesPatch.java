/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.reddit.profile.model.ProfileFeedType;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import kotlin.jvm.functions.Function0;

@SuppressWarnings("unused")
public final class SearchHiddenProfilesPatch {

    /**
     * Added to Reddit's profile empty feed model during patching.
     */
    public interface EmptyFeedModel {
        @Nullable
        ProfileFeedType patch_getFeedType();

        @Nullable
        String patch_getProfileName();

        /**
         * If the posts or comments are hidden by the profile settings.
         * A feed filtered by flair is never also filtered by the profile settings.
         */
        boolean patch_isFiltered();
    }

    /**
     * Opens Reddit search with the posts of a profile.
     * A record, so Compose sees equal click handlers and skips recomposition.
     */
    private record SearchClickHandler(String profileName) implements Function0<Object> {

        @Override
        public Object invoke() {
            try {
                Activity activity = Utils.getActivity();
                if (activity == null) {
                    Logger.printDebug(() -> "No activity to open search with");
                    return null;
                }

                // Reddit opens its own search results screen for search links.
                // Search ignores the author filter if the name is not quoted.
                Uri uri = new Uri.Builder()
                        .scheme("https")
                        .authority("www.reddit.com")
                        .path("/search/")
                        .appendQueryParameter("q", "author:\"" + profileName + "\"")
                        .appendQueryParameter("sort", "new")
                        .build();
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.setPackage(activity.getPackageName());
                activity.startActivity(intent);
            } catch (Exception ex) {
                Logger.printException(() -> "SearchClickHandler failure", ex);
            }
            return null;
        }
    }

    /**
     * If the empty feed being composed shows the search button.
     * Set right before the empty feed and its button label are composed, on the main thread.
     */
    private static boolean emptyFeedShowsSearch;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return A click handler that searches the posts of the profile, if the feed is a visitor
     *         feed with posts or comments the profile hides. Otherwise, the original click handler.
     */
    public static Function0<?> getEmptyFeedButtonClickHandler(EmptyFeedModel model, Function0<?> original) {
        try {
            if (!Settings.SEARCH_HIDDEN_PROFILES.get()) {
                return original;
            }

            ProfileFeedType feedType = model.patch_getFeedType();
            final boolean isVisitorFeed = feedType == ProfileFeedType.VISITOR_POST
                    || feedType == ProfileFeedType.VISITOR_COMMENT;
            String profileName = model.patch_getProfileName();
            if (!isVisitorFeed || !model.patch_isFiltered()
                    || profileName == null || profileName.isEmpty()) {
                return original;
            }

            return new SearchClickHandler(profileName);
        } catch (Exception ex) {
            Logger.printException(() -> "getEmptyFeedButtonClickHandler failure", ex);
            return original;
        }
    }

    /**
     * Injection point.
     */
    public static boolean showEmptyFeedButton(boolean original, Function0<?> clickHandler) {
        emptyFeedShowsSearch = clickHandler instanceof SearchClickHandler;
        return original || emptyFeedShowsSearch;
    }

    /**
     * Injection point.
     */
    public static String getEmptyFeedButtonLabel(String original) {
        return emptyFeedShowsSearch
                ? str("morphe_search_hidden_profiles_button")
                : original;
    }
}
