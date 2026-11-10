/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class ShuffleButtonAsLoopPatch {

    /**
     * Injection point.
     *
     * @return If the shuffle control of the media session should be replaced with the loop control.
     */
    public static boolean replaceShuffleWithLoop() {
        return Settings.SHUFFLE_BUTTON_AS_LOOP.get();
    }
}
