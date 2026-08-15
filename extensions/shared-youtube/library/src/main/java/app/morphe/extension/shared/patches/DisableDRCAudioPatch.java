/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public final class DisableDRCAudioPatch {

    /**
     * Injection point.
     */
    public static boolean disableDrcAudio() {
        return SharedYouTubeSettings.DISABLE_DRC_AUDIO.get();
    }
}
