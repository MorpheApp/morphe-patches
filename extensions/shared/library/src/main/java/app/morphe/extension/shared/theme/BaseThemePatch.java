package app.morphe.extension.shared.theme;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public abstract class BaseThemePatch {
    // Background colors.
    protected static final int BLACK_COLOR = ResourceUtils.getColor("yt_black1");
    protected static final int WHITE_COLOR = ResourceUtils.getColor("yt_white1");

    /**
     * Check if a value matches any of the provided values.
     *
     * @param value The value to check.
     * @param of    The array of values to compare against.
     * @return True if the value matches any of the provided values.
     */
    protected static boolean anyEquals(int value, int... of) {
        for (int v : of) {
            if (value == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to process color values for Litho components.
     *
     * @param originalValue The original color value.
     * @param darkValues    Array of dark mode color values to match.
     * @param lightValues   Array of light mode color values to match.
     * @return The new or original color value.
     */
    protected static int processColorValue(int originalValue, int[] darkValues, @Nullable int[] lightValues) {
        int[] originalColors;
        int replacementColor;

        if (Utils.isDarkModeEnabled()) {
            originalColors = darkValues;
            replacementColor = BLACK_COLOR;
        } else {
            if (lightValues == null) return originalValue;
            originalColors = lightValues;
            replacementColor = WHITE_COLOR;
        }

        for (int color : originalColors) {
            if (originalValue == color) {
                // Use same alpha value of the original color.
                return (replacementColor & 0x00FFFFFF) | (color & 0xFF000000);
            }
        }

        return originalValue;
    }
}
