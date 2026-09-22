/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.CustomBrandingPatch;

@SuppressWarnings("unused")
public class StartupAnimationPatch {

    /**
     * Injection point.
     *
     * @return The view that plays the startup animation, or null to skip the animation.
     */
    @Nullable
    public static View getLottieViewOrNull(View lottieStartupView) {
        return Settings.STARTUP_ANIMATION.get()
                ? CustomBrandingPatch.getLottieViewOrNull(lottieStartupView)
                : null;
    }
}
