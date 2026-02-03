package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

/** @noinspection unused */
public final class AmbientModePatch {

    /**
     * Constant found in: androidx.window.embedding.DividerAttributes
     */
    private static final int DIVIDER_ATTRIBUTES_COLOR_SYSTEM_DEFAULT = -16777216;

    /**
     * Bypass Ambient mode restrictions.
     */
    public static boolean bypassAmbientModeRestrictions(boolean isPowerSaveMode) {
        if (Settings.BYPASS_AMBIENT_MODE_RESTRICTIONS.get()) {
            return false;
        }

        if (Settings.DISABLE_AMBIENT_MODE.get()) {
            return true;
        }

        return isPowerSaveMode;
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
