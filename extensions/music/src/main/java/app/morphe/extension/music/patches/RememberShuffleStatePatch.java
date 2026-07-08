/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.util.Log;
import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class RememberShuffleStatePatch {

    /**
     * Injection point.
     */
    public static void saveShuffleState(Enum<?> shuffleState) {
        if (Settings.REMEMBER_SHUFFLE_STATE.get()) {
            Settings.SAVED_SHUFFLE_STATE.save(shuffleState.ordinal() == 1);
        }
    }

    /**
     * Injection point.
     */
    public static boolean overrideShuffleButton(boolean original) {
        if (!Settings.REMEMBER_SHUFFLE_STATE.get()) {
            return original;
        }
        return Settings.SAVED_SHUFFLE_STATE.get();
    }

    /**
     * Injection point.
     */
    public static void applySavedShuffleState(String videoId) {
        if (!Settings.REMEMBER_SHUFFLE_STATE.get() || !Settings.SAVED_SHUFFLE_STATE.get()) {
            return;
        }

        shuffleTracks();
    }

    /**
     * Injection point.
     */
    public static void shuffleTracks() {
        Log.d("Morphe: RememberShuffle", "Tracks are shuffled");
    }
}
