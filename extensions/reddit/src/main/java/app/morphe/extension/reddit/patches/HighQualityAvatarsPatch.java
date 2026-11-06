/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4477
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HighQualityAvatarsPatch {

    /**
     * Uploaded avatar images, and Snoo avatars of some users.
     */
    private static final String PROFILE_ICON_URL_PREFIX = "https://styles.redditmedia.com/";
    private static final String PROFILE_ICON_URL_PATH = "/styles/profileIcon_";

    /**
     * Snoo avatars. The preview host needs the signed query,
     * so the original is loaded from the image host instead.
     */
    private static final String SNOOVATAR_PREVIEW_URL_PREFIX = "https://preview.redd.it/snoovatar/avatars/";
    private static final String SNOOVATAR_ORIGINAL_URL_PREFIX = "https://i.redd.it/snoovatar/avatars/";

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * Avatars are loaded with a signed resize query, such as 50x50 pixels for comments.
     * The signature prevents asking for a larger size, but without the query
     * the original image is loaded (256x256 for Snoo avatars).
     *
     * @param model Glide image model, which is an url string for avatars.
     */
    @Nullable
    public static Object getImageModel(@Nullable Object model) {
        try {
            if (!Settings.HIGH_QUALITY_AVATARS.get() || !(model instanceof String)) {
                return model;
            }

            String url = (String) model;
            final int queryIndex = url.indexOf('?');
            if (queryIndex < 0
                    // Never show the original of a blurred image.
                    || url.indexOf("blur=", queryIndex) >= 0) {
                return model;
            }

            if (url.startsWith(SNOOVATAR_PREVIEW_URL_PREFIX)) {
                return SNOOVATAR_ORIGINAL_URL_PREFIX
                        + url.substring(SNOOVATAR_PREVIEW_URL_PREFIX.length(), queryIndex);
            }

            if (url.startsWith(PROFILE_ICON_URL_PREFIX)
                    && url.lastIndexOf(PROFILE_ICON_URL_PATH, queryIndex) >= 0) {
                return url.substring(0, queryIndex);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getImageModel failure", ex);
        }

        return model;
    }
}
