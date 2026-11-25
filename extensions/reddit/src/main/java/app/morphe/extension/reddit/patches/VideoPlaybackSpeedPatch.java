/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5308
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.features.VideoPlaybackSpeedVariant;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class VideoPlaybackSpeedPatch {

    public enum PlaybackSpeedControls {
        DEFAULT,
        MENU,
        MENU_AND_HOLD
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    @Nullable
    public static VideoPlaybackSpeedVariant getVariant(@Nullable VideoPlaybackSpeedVariant original) {
        try {
            return switch (Settings.VIDEO_PLAYBACK_SPEED_CONTROLS.get()) {
                case DEFAULT -> original;
                case MENU -> VideoPlaybackSpeedVariant.OVERFLOW_MENU;
                case MENU_AND_HOLD -> VideoPlaybackSpeedVariant.COMBINED_WITH_LOCK;
            };
        } catch (Exception ex) {
            Logger.printException(() -> "getVariant failure", ex);
            return original;
        }
    }
}
