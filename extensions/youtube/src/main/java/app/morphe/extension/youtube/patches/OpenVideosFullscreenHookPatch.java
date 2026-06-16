/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation.PlaybackController;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class OpenVideosFullscreenHookPatch {

    public enum OpenFullscreenMode {
        DISABLED,
        PORTRAIT,
        LANDSCAPE,
    }

    public interface FullscreenInterface {
        void patch_exitFullscreen();
        void patch_enterFullscreen();
    }

    private static volatile WeakReference<FullscreenInterface> fullscreenInterfaceRef = new WeakReference<>(null);

    @Nullable
    private static volatile Boolean openNextVideoFullscreen;

    public static void setFullscreenInterface(FullscreenInterface fullscreenInterface) {
        fullscreenInterfaceRef = new WeakReference<>(fullscreenInterface);
    }

    public static void setOpenNextVideoFullscreen(@Nullable Boolean forceFullScreen) {
        openNextVideoFullscreen = forceFullScreen;
    }

    public static void exitFullscreenMode() {
        FullscreenInterface screenInterface = fullscreenInterfaceRef.get();
        if (screenInterface == null) {
            Logger.printException(() -> "Cannot exit fullscreen mode (interface is null)");
            return;
        }

        Logger.printDebug(() -> "Exiting fullscreen mode");
        screenInterface.patch_exitFullscreen();
    }

    /**
     * Changed during patching since this class is also
     * used by {@link OpenVideosFullscreenHookPatch}.
     */
    private static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Returns negated value.
     */
    public static boolean doNotOpenVideoFullscreenPortrait(boolean original) {
        Boolean openFullscreen = openNextVideoFullscreen;
        if (openFullscreen != null) {
            openNextVideoFullscreen = null;
            return !openFullscreen;
        }

        if (!isPatchIncluded()) {
            return original;
        }

        return Settings.OPEN_VIDEOS_FULLSCREEN.get() != OpenFullscreenMode.PORTRAIT;
    }

    private static boolean shouldEnterFullscreen;

    /**
     * Injection point.
     */
    public static void initialize(PlaybackController playerController) {
        shouldEnterFullscreen = Settings.OPEN_VIDEOS_FULLSCREEN.get() == OpenFullscreenMode.LANDSCAPE;
    }

    /**
     * Injection point.
     */
    public static void playerStatusChanged(Enum<?> status) {
        try {
            if (!shouldEnterFullscreen) return;
            if (status == null || !"VIDEO_PLAYING".equals(status.name())) return;

            if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN) {
                shouldEnterFullscreen = false;
                return;
            }
            shouldEnterFullscreen = false;

            FullscreenInterface screenInterface = fullscreenInterfaceRef.get();
            if (screenInterface == null) {
                Logger.printException(() -> "Cannot enter fullscreen (interface is null)");
                return;
            }

            Logger.printDebug(() -> "Opening video fullscreen");
            Utils.verifyOnMainThread();
            screenInterface.patch_enterFullscreen();
        } catch (Exception ex) {
            Logger.printException(() -> "playerStatusChanged failure", ex);
        }
    }
}
