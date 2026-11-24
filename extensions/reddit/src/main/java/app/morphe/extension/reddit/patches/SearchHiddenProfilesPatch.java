/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SearchHiddenProfilesPatch {

    /**
     * Added to Reddit's profile model during patching.
     */
    public interface ProfileModel {
        @Nullable
        String patch_getUsername();

        /**
         * If the posts or comments are hidden by the profile settings.
         */
        boolean patch_isProfileFiltered();
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * Reddit search scoped to a profile finds nothing if the profile hides its posts,
     * but a search for the author of the posts still finds them.
     *
     * @return If the search results of the posts of the profile were opened, instead of
     *         the search scoped to the profile, because the profile hides its posts or comments.
     */
    public static boolean openProfileSearch(ProfileModel model) {
        try {
            if (!Settings.SEARCH_HIDDEN_PROFILES.get() || !model.patch_isProfileFiltered()) {
                return false;
            }

            String username = model.patch_getUsername();
            if (username == null || username.isEmpty()) {
                return false;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) {
                Logger.printDebug(() -> "No activity to open search with");
                return false;
            }

            // Reddit opens its own search results screen for search links.
            // Search ignores the author filter if the name is not quoted.
            Uri uri = new Uri.Builder()
                    .scheme("https")
                    .authority("www.reddit.com")
                    .path("/search/")
                    .appendQueryParameter("q", "author:\"" + username + "\"")
                    .appendQueryParameter("sort", "new")
                    .build();
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(activity.getPackageName());
            activity.startActivity(intent);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "openProfileSearch failure", ex);
            return false;
        }
    }
}
