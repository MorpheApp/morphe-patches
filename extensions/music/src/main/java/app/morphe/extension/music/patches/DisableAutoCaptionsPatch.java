/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4773
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class DisableAutoCaptionsPatch {

    /**
     * Reason of a caption track event when the captions are enabled automatically,
     * after the caption tracks of a video are loaded.
     */
    private static final int CAPTION_TRACK_REASON_AUTOMATIC = 2;

    /**
     * Injection point.
     *
     * @param isDisableCaptionsOption If the caption track is the option to disable captions.
     * @param reason                  Reason the caption track is set.
     * @return If the caption track is used as the option to disable captions.
     */
    public static boolean disableAutoCaptions(boolean isDisableCaptionsOption, int reason) {
        if (!isDisableCaptionsOption
                && reason == CAPTION_TRACK_REASON_AUTOMATIC
                && Settings.DISABLE_AUTO_CAPTIONS.get()) {
            Logger.printDebug(() -> "Disabling auto captions");
            return true;
        }

        return isDisableCaptionsOption;
    }

    /**
     * Injection point.
     *
     * @param hasDefaultTrack If the caption tracks of the video have a default track,
     *                        which is used if captions are disabled or no caption track is set.
     */
    public static boolean disableDefaultCaptionTrack(boolean hasDefaultTrack) {
        if (hasDefaultTrack && Settings.DISABLE_AUTO_CAPTIONS.get()) {
            Logger.printDebug(() -> "Disabling default caption track");
            return false;
        }

        return hasDefaultTrack;
    }
}
