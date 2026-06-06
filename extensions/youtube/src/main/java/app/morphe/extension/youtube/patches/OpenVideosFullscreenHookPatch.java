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
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OpenVideosFullscreenHookPatch {

    public interface FullscreenInterface {
        void patch_exitFullscreen();
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
     *
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

        return !Settings.OPEN_VIDEOS_FULLSCREEN_PORTRAIT.get();
    }
}
