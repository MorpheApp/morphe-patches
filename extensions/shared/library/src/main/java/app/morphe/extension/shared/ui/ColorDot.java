/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original first edition code:
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/584b00fd87f83504b8886e4f3f674f8c3943cd91
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/2e9d6959c94df7588b9e34b18770e9f437e91926
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/ece8076f7cefd752b97515014bc50fe4fd80171e
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/2b62fc2224c42da024fd64602346ff30613517c0
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/a426e2af5086367a2a1fee83abbbd2ea230bda06
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/584b00fd87f83504b8886e4f3f674f8c3943cd91
 * https://gitlab.com/ReVanced/revanced-patches/-/commit/14a8f4fb96f5e2a4bc264a54115e0870b1a1ffa8
 * https://github.com/MorpheApp/morphe-patches/commit/f5371ca998c019609c2b5558b3408ab1fec065c8
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.ui;

import static app.morphe.extension.shared.Utils.adjustColorBrightness;
import static app.morphe.extension.shared.Utils.isDarkModeEnabled;
import static app.morphe.extension.shared.settings.preference.ColorPickerPreference.DISABLED_ALPHA;
import static app.morphe.extension.shared.theme.ThemeUtils.getAppBackgroundColor;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.ColorInt;
import app.morphe.extension.shared.theme.ThemeUtils;

public class ColorDot {
    private static final int STROKE_WIDTH = Dim.dp(1.5f);

    /**
     * Creates a circular drawable with a main fill and a stroke.
     * Stroke adapts to dark/light theme and transparency, applied only when color is transparent or matches app background.
     */
    public static GradientDrawable createColorDotDrawable(@ColorInt int color) {
        final boolean isDarkTheme = isDarkModeEnabled();
        final boolean isTransparent = Color.alpha(color) == 0;
        final int opaqueColor = color | 0xFF000000;
        final int appBackground = getAppBackgroundColor();
        final int strokeColor;
        final int strokeWidth;

        // Determine stroke color.
        if (isTransparent || (opaqueColor == appBackground)) {
            final int baseColor = isTransparent ? appBackground : opaqueColor;
            strokeColor = adjustColorBrightness(baseColor, isDarkTheme ? 1.2f : 0.8f);
            strokeWidth = STROKE_WIDTH;
        } else {
            strokeColor = 0;
            strokeWidth = 0;
        }

        // Create circular drawable with conditional stroke.
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        circle.setStroke(strokeWidth, strokeColor);

        return circle;
    }

    /**
     * Applies the color dot drawable to the target view.
     */
    public static void applyColorDot(View targetView, @ColorInt int color, boolean enabled) {
        if (targetView == null) return;
        targetView.setBackground(createColorDotDrawable(color));
        targetView.setAlpha(enabled ? 1.0f : DISABLED_ALPHA);
        if (!isDarkModeEnabled()) {
            targetView.setClipToOutline(true);
            targetView.setElevation(Dim.dp2);
        }
    }
}
