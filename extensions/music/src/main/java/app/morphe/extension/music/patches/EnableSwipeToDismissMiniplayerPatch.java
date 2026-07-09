/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class EnableSwipeToDismissMiniplayerPatch {

    /**
     * Injection point
     */
    public static boolean enableSwipeToDismissMiniplayer() {
        return Settings.ENABLE_SWIPE_TO_DISMISS_MINIPLAYER.get();
    }

    /**
     * Injection point
     */
    public static Object enableSwipeToDismissMiniplayer(Object object) {
        return Settings.ENABLE_SWIPE_TO_DISMISS_MINIPLAYER.get() ? null : object;
    }
}