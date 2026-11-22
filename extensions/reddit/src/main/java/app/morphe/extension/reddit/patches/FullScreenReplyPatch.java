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
    /**
     * When this experiment is off, replies open in the older full-screen editor.
     * The experiment "android_modern_comment_composer_m2" only changes details of the
     * modern composer, so it is left as is.
     */
    private static final String MODERN_COMMENT_COMPOSER_EXPERIMENT = "android_modern_comment_composer";

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
    public static boolean useFullScreenReply(String experimentName, boolean original) {
        if (!MODERN_COMMENT_COMPOSER_EXPERIMENT.equals(experimentName)) {
            return original;
        }

        // The flag can be read before the main activity has launched.
        if (!Utils.isContextSet()) {
            Logger.printInfo(() -> "Cannot use full-screen reply, context is not set");
            return original;
        }

        if (Settings.FULL_SCREEN_REPLY.get()) {
            return false;
        }

        return original;
    }
}
