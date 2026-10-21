/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3907
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CaptionsInPipPatch {

    private static final String CAPTIONS_OVERLAY = "player_overlay_caption";

    /**
     * Injection point. Called while in picture-in-picture, where YouTube hides most player overlays.
     */
    public static boolean isPipActive(String overlayName) {
        return !(Settings.CAPTIONS_IN_PIP.get() && CAPTIONS_OVERLAY.equals(overlayName));
    }

    /**
     * Injection point. Used by versions that give overlays the player type instead.
     *
     * @return If the overlay must be shown, even though the player type hides it.
     */
    public static boolean showInPip(String overlayName, Enum<?> playerType) {
        return "WATCH_WHILE_PICTURE_IN_PICTURE".equals(playerType.name()) && !isPipActive(overlayName);
    }

    /**
     * Injection point. Older versions can set the current caption track again when entering or leaving
     * picture-in-picture, which clears the caption on screen until the next one.
     */
    public static boolean isCurrentTrack(Object track, Object currentTrack) {
        return Settings.CAPTIONS_IN_PIP.get() && track != null && track.equals(currentTrack);
    }
}
