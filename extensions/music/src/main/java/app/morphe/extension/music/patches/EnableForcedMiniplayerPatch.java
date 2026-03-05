/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class EnableForcedMiniplayerPatch {

    /**
     * Injection point
     */
    public static boolean enableForcedMiniplayerPatch(boolean original) {
        return Settings.ENABLE_FORCED_MINIPLAYER.get() || original;
    }
}