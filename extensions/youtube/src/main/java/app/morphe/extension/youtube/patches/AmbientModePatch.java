package app.morphe.extension.youtube.patches;

import android.os.PowerManager;

import java.util.List;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.ambientlight.AmbientLightController;
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
        return !Settings.DISABLE_AMBIENT_MODE.get() && !AmbientLightController.replacesAmbientMode() && original;
    }

    /**
     * Injection point for fullscreen background color.
     */
    public static int getFullScreenBackgroundColor(int originalColor) {
        if (Settings.DISABLE_FULLSCREEN_AMBIENT_MODE.get() || AmbientLightController.replacesAmbientMode()) {
            return DIVIDER_ATTRIBUTES_COLOR_SYSTEM_DEFAULT;
        }

        return originalColor;
    }

    /**
     * Enhanced Ambient mode turns its light off in fullscreen too, and ignores "Disable Ambient mode".
     */
    public static final class DisableFullscreenAmbientModeAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.ENHANCED_AMBIENT_MODE.get() || !Settings.DISABLE_AMBIENT_MODE.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.ENHANCED_AMBIENT_MODE, Settings.DISABLE_AMBIENT_MODE);
        }
    }
}
