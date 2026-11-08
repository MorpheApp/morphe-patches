package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class HideSeekbarPatch {
    /**
     * Injection point.
     */
    public static boolean hideSeekbar() {
        return Settings.HIDE_SEEKBAR.get();
    }

    /**
     * Injection point.
     */
    public static boolean useFullscreenLargeSeekbar(boolean original) {
        return Logger.traceOverride("HideSeekbarPatch.useFullscreenLargeSeekbar", original,
                Settings.FULLSCREEN_LARGE_SEEKBAR.get());
    }
}
