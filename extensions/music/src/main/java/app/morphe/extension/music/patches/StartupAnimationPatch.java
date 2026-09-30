/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.graphics.Color;

import com.airbnb.lottie.LottieAnimationView;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.SplashAnimationPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

@SuppressWarnings("unused")
public class StartupAnimationPatch {

    /**
     * Gray the black and white animations of YouTube draw the progress track with.
     */
    private static final int TRACK_COLOR = Color.rgb(128, 128, 128);

    /**
     * How long the app waits before the mark starts moving. The app drops the first frames of
     * an animation while it is still starting up, which makes the start of it look torn.
     */
    private static final long START_DELAY_MS = 170;

    /**
     * Injection point.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        // The app plays the animation as soon as this returns, and Lottie plays an animation
        // that is set later as soon as it has it, so setting it later is what delays the start.
        Utils.runOnMainThreadDelayed(() -> setSplashAnimation(view, resourceId), START_DELAY_MS);
    }

    private static void setSplashAnimation(LottieAnimationView view, int resourceId) {
        try {
            // A custom branding icon replaces the YT Music logo animation with its own.
            if (SplashAnimationPatch.setBrandedSplashAnimation(view)) {
                return;
            }

            if (SplashAnimationPatch.isMonochrome()) {
                // The app has no black and white animation of its own. The logo is drawn in the
                // foreground color of the theme, and the progress track in gray, the same way
                // the black and white animations of YouTube are drawn.
                final int foregroundColor = ThemeUtils.getAppForegroundColor();

                SplashAnimationPatch.setSplashAnimation(view, resourceId, Map.of(
                        // Logo of the app, including the frames where its color animates.
                        "[1,0,0.2,1]", foregroundColor,
                        "[1,0.152941176471,0.56862745098,1]", foregroundColor,
                        "[1,1,1,1]", foregroundColor,
                        // Track the progress of the loading is drawn on.
                        "[0.8,0.8,0.8,1]", TRACK_COLOR,
                        "[0.800000011921,0.800000011921,0.800000011921,1]", TRACK_COLOR
                ));
                return;
            }

            view.patch_setAnimation(resourceId);
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashAnimation failure", ex);
        }
    }
}
