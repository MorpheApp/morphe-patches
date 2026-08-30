/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2628
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisablePlaylistAutoplayPatch {

    /**
     * Injection point.
     */
    public static boolean shouldSkipPlaylistAutoplay(Enum<?> navigationIntent) {
        return Settings.DISABLE_PLAYLIST_AUTOPLAY.get() && "AUTOPLAY".equals(navigationIntent.name());
    }
}
