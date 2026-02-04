package app.morphe.extension.youtube.patches;

import android.os.PowerManager;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AmbientModePatch {

    /**
     * Constant found in: androidx.window.embedding.DividerAttributes
     */
    private static final int DIVIDER_ATTRIBUTES_COLOR_SYSTEM_DEFAULT = -16777216;

    /**
     * Bypass Ambient mode restrictions.
     * @return {@link PowerManager#isPowerSaveMode()}
     */
    public static boolean bypassAmbientModeRestrictions(PowerManager powerManager) {
        return !Settings.BYPASS_AMBIENT_MODE_RESTRICTIONS.get() && powerManager.isPowerSaveMode();
    }

    /**
     * Disable Ambient mode.
     */
    public static boolean disableAmbientMode(boolean original) {
        return !Settings.DISABLE_AMBIENT_MODE.get() && original;
    }

    /**
     * Disable Ambient mode logic when entering fullscreen.
     */
    public static boolean disableAmbientModeInFullscreen() {
        return !Settings.DISABLE_FULLSCREEN_AMBIENT_MODE.get();
    }

    /**
     * Injection point for fullscreen background color.
     */
    public static int getFullScreenBackgroundColor(int originalColor) {
        if (Settings.DISABLE_FULLSCREEN_AMBIENT_MODE.get()) {
            return DIVIDER_ATTRIBUTES_COLOR_SYSTEM_DEFAULT;
        }

        return originalColor;
    }
}
