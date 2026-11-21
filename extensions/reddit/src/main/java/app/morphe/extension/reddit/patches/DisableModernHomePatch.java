/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class DisableModernHomePatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean disableModernHome(boolean original) {
        return !Settings.DISABLE_MODERN_HOME.get() && original;
    }

    /**
     * Injection point.
     *
     * @param variant Variant of the For You and Following home experiment.
     * @return Null if the modern home is disabled, which shows the home of a user
     *         who is not in the experiment.
     */
    @Nullable
    public static Object disableForYouFollowing(@Nullable Object variant) {
        return Settings.DISABLE_MODERN_HOME.get() ? null : variant;
    }
}
