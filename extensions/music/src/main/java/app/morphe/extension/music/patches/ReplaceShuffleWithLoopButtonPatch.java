/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class ReplaceShuffleWithLoopButtonPatch {

    /**
     * The loop button replaces the shuffle button in the same slot of the media controls, so it
     * is not used while 'Hide previous & next buttons' is on, which needs the space for other
     * buttons. The option is greyed out instead of being
     * turned off, so the user keeps the value they picked.
     */
    public static final class ReplaceShuffleWithLoopButtonAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return isReplaceShuffleWithLoopButtonAvailable();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(SharedYouTubeSettings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT);
        }
    }

    private static boolean isReplaceShuffleWithLoopButtonAvailable() {
        // To minimize confusion, it works only when 'Hide previous & next buttons' is turned off.
        return !SharedYouTubeSettings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get();
    }

    /**
     * Injection point.
     *
     * @return If the shuffle control of the media session should be replaced with the loop control.
     */
    public static boolean replaceShuffleWithLoop() {
        return Settings.REPLACE_SHUFFLE_WITH_LOOP_BUTTON.get() && isReplaceShuffleWithLoopButtonAvailable();
    }
}
