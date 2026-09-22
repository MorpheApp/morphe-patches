/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import com.airbnb.lottie.LottieAnimationView;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.SplashAnimationPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

@SuppressWarnings("unused")
public class StartupAnimationPatch {

    /**
     * Injection point.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        try {
            // A custom branding icon replaces the YT Music logo animation with its own.
            if (SplashAnimationPatch.setBrandedSplashAnimation(view)) {
                return;
            }

            if (SplashAnimationPatch.isMonochrome()) {
                // The app has no black and white animation of its own, so the colors of the
                // original one are replaced with the foreground color of the theme.
                final int foregroundColor = ThemeUtils.getAppForegroundColor();

                SplashAnimationPatch.setSplashAnimation(view, resourceId, Map.of(
                        "[1,0,0.2,1]", foregroundColor,
                        "[1,0.152941176471,0.56862745098,1]", foregroundColor
                ));
                return;
            }

            view.patch_setAnimation(resourceId);
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashAnimationLottie failure", ex);
        }
    }
}
