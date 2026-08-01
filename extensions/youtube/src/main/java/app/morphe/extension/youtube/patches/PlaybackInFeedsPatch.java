/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2261
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Duplicate of the 'Playback in feeds' setting of YouTube, which is not shown
 * in the tablet layout and can also be remotely removed from the settings.
 */
@SuppressWarnings("unused")
public final class PlaybackInFeedsPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlaybackInFeedsController {
        // Methods are added during patching.
        int patch_getPlaybackInFeedsMode();
        void patch_setPlaybackInFeedsMode(int mode);
    }

    /** Videos in feeds never play automatically. */
    public static final int MODE_OFF = 0;

    /** Videos in feeds play automatically only on Wi-Fi. */
    public static final int MODE_WIFI_ONLY = 1;

    /** Videos in feeds always play automatically. */
    public static final int MODE_ALWAYS_ON = 2;

    @Nullable
    private static volatile PlaybackInFeedsController controller;

    /**
     * Injection point.
     */
    public static void setController(PlaybackInFeedsController instance) {
        controller = instance;

        // Refresh the copy on startup, otherwise exporting can save a stale mode
        // if the mode was changed using the settings of YouTube.
        // Must not run inline, since YouTube is still inside its own constructor.
        Utils.runOnMainThread(PlaybackInFeedsPatch::updateSettingFromApp);
    }

    /**
     * @return The mode YouTube currently uses, or the last known mode if YouTube
     *         did not create its controller yet.
     */
    private static int getMode() {
        try {
            PlaybackInFeedsController instance = controller;
            if (instance != null) {
                final int mode = instance.patch_getPlaybackInFeedsMode();
                if (isValidMode(mode)) {
                    return mode;
                }

                Logger.printException(() -> "Unknown playback in feeds mode: " + mode);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getMode failure", ex);
        }

        return Settings.PLAYBACK_IN_FEEDS.get();
    }

    public static void setMode(int mode) {
        try {
            if (!isValidMode(mode)) {
                Logger.printException(() -> "Cannot set unknown playback in feeds mode: " + mode);
                return;
            }

            // Changing a preference also sets the value it already has,
            // and that must not overwrite the mode YouTube uses.
            if (mode == getMode()) {
                return;
            }

            PlaybackInFeedsController instance = controller;
            if (instance == null) {
                Logger.printDebug(() -> "Cannot set mode, controller is not set: " + mode);
                return;
            }

            instance.patch_setPlaybackInFeedsMode(mode);
        } catch (Exception ex) {
            Logger.printException(() -> "setMode failure", ex);
        }
    }

    /**
     * Copies the mode YouTube currently uses to {@link Settings#PLAYBACK_IN_FEEDS},
     * so the Morphe setting shows the same value as the native setting.
     */
    public static void updateSettingFromApp() {
        try {
            if (controller == null) {
                return;
            }

            Settings.PLAYBACK_IN_FEEDS.save(getMode());
        } catch (Exception ex) {
            Logger.printException(() -> "updateSettingFromApp failure", ex);
        }
    }

    private static boolean isValidMode(int mode) {
        return mode >= MODE_OFF && mode <= MODE_ALWAYS_ON;
    }
}
