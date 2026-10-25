package app.morphe.extension.youtube.swipecontrols.controller;

import android.view.WindowManager;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;

/**
 * Controller to adjust the screen brightness level.
 */
public final class ScreenBrightnessController {

    /**
     * The host activity of which the brightness is adjusted, the main controller instance.
     */
    public final SwipeControlsHostActivity host;

    // Flag that indicates whether the brightness has been restored
    private boolean isBrightnessRestored;

    /**
     * @param host The host activity of which the brightness is adjusted, the main controller instance.
     */
    public ScreenBrightnessController(SwipeControlsHostActivity host) {
        this.host = host;
    }

    /**
     * The current screen brightness in percent, ranging from 0.0 to 100.0.
     */
    public double getScreenBrightness() {
        return getRawScreenBrightness() * 100.0;
    }

    public void setScreenBrightness(double value) {
        setRawScreenBrightness(Utils.clamp((float) value / 100f, 0f, 1f));
    }

    /**
     * Restores the screen brightness to the default device brightness.
     */
    public void restoreDefaultBrightness() {
        setRawScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    /**
     * Saves the current screen brightness into settings, to be brought back using {@link #restore()}.
     */
    public void save() {
        if (isBrightnessRestored) {
            // Saves the current screen brightness value into settings
            host.getConfig().setSavedScreenBrightnessValue(getRawScreenBrightness());
            // Reset the flag
            isBrightnessRestored = false;
        }
    }

    /**
     * Restores the screen brightness from settings saved using {@link #save()}.
     */
    public void restore() {
        // Restores the screen brightness value from the saved settings
        setRawScreenBrightness(host.getConfig().getSavedScreenBrightnessValue());
        // Mark that brightness has been restored
        isBrightnessRestored = true;
    }

    /**
     * Wrapper for the raw screen brightness in {@link WindowManager.LayoutParams#screenBrightness}.
     */
    public float getRawScreenBrightness() {
        return host.getWindow().getAttributes().screenBrightness;
    }

    private void setRawScreenBrightness(float value) {
        WindowManager.LayoutParams attr = host.getWindow().getAttributes();
        attr.screenBrightness = value;
        host.getWindow().setAttributes(attr);
    }
}
