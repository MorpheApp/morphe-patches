/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5191
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class FullScreenReplyPatch {
    private static final String MODERN_COMMENT_COMPOSER_EXPERIMENT = "android_modern_comment_composer";

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static boolean isContextNotYetSet() {
        // Possible fix for background crash of app when context is not yet set.
        if (!Utils.isContextSet()) {
            Logger.printInfo(() -> "Cannot use full screen reply, context is null");
            return true;
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean useFullScreenReply(String experimentName, boolean original) {
        // Possible fix for background crash of app when context is not yet set.
        if (isContextNotYetSet()) {
            return original;
        }
        if (Settings.FULL_SCREEN_REPLY.get()
                && MODERN_COMMENT_COMPOSER_EXPERIMENT.equals(experimentName)) {
            return false;
        }

        return original;
    }
}
