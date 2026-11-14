/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4617
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class MusicMediaNotificationControlsPatch {

    /**
     * Without the previous and next buttons the media controls have room
     * for both the shuffle and the loop button, so nothing is replaced.
     */
    private static final boolean REPLACE_SHUFFLE_WITH_LOOP_BUTTON =
            Settings.REPLACE_SHUFFLE_WITH_LOOP_BUTTON.get()
                    && !SharedYouTubeSettings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get();

    /**
     * Injection point.
     *
     * @return If the shuffle action of the media session should not be shown,
     *         which lets the loop action take its place.
     */
    public static boolean hideShuffleAction() {
        return REPLACE_SHUFFLE_WITH_LOOP_BUTTON;
    }
}
