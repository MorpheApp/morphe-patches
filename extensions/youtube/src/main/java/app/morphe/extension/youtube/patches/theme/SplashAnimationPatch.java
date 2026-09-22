/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.theme;

import android.graphics.Color;

import com.airbnb.lottie.LottieAnimationView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.CustomBrandingPatch;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * The animation the app plays while it starts.
 */
@SuppressWarnings("unused")
public final class SplashAnimationPatch {

    /**
     * Color the branded monochrome animation is drawn with in the file, as it is written in
     * the JSON. The mark never uses it, so nothing else of the file is replaced with it.
     */
    private static final String MONOCHROME_PLACEHOLDER_COLOR = "[1,0,1,1]";

    /**
     * Injection point.
     */
    public static boolean useLotteLaunchSplashScreen(boolean original) {
        Logger.printDebug(() -> "Lottie splash screen flag: " + original);
        return true; // Force lottie animation view.
    }

    /**
     * Injection point.
     * Modern Lottie style animation.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        try {
            ThemePatch.SplashScreenAnimationStyle animationStyle = Settings.SPLASH_SCREEN_ANIMATION_STYLE.get();
            final boolean blackAndWhite =
                    animationStyle == ThemePatch.SplashScreenAnimationStyle.FPS_30_BLACK_AND_WHITE
                            || animationStyle == ThemePatch.SplashScreenAnimationStyle.FPS_60_BLACK_AND_WHITE;

            // A custom branding icon replaces the YouTube logo animation with its own,
            // and follows the black and white style with a single color version of it.
            final int brandingAnimation = CustomBrandingPatch.getStartupAnimation(blackAndWhite);
            if (brandingAnimation != 0) {
                if (blackAndWhite) {
                    setAnimationWithColors(view, brandingAnimation, Map.of(
                            MONOCHROME_PLACEHOLDER_COLOR, ThemeUtils.getAppForegroundColor()
                    ));
                } else {
                    view.patch_setAnimation(brandingAnimation);
                }
                return;
            }

            if (!SeekbarColorPatch.isCustomSeekbarColorEnabled()
                    // Black and white animations cannot use color replacements.
                    || blackAndWhite) {
                view.patch_setAnimation(resourceId);
                return;
            }

            // Must specify primary key name otherwise the morphing YT logo color is also changed.
            setAnimationWithColors(view, resourceId, Map.of(
                    "[1,0,0.2,1]", SeekbarColorPatch.getSeekbarColor(),
                    "[1,0.152941176471,0.56862745098,1]", SeekbarColorPatch.getSeekbarAccentColor()
            ));
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashAnimationLottie failure", ex);
        }
    }

    /**
     * Plays a Lottie animation with some of the colors of the file replaced.
     *
     * @param replacements The color of the file, as it is written in the JSON, and its replacement.
     */
    private static void setAnimationWithColors(LottieAnimationView view, int resourceId,
                                               Map<String, Integer> replacements) {
        final String key = "\"k\":";
        String json = loadRawResourceAsString(resourceId);
        String replacement = json;

        for (Map.Entry<String, Integer> entry : replacements.entrySet()) {
            if (BaseSettings.DEBUG.get() && !json.contains(key + entry.getKey())) {
                Logger.printException(() -> "Could not replace splash animation colors: " + json);
            }
            replacement = replacement.replace(key + entry.getKey(),
                    key + getColorStringArray(entry.getValue()));
        }

        // cacheKey is not needed since the animation will not be reused.
        view.patch_setAnimation(new ByteArrayInputStream(replacement.getBytes()), null);
    }

    private static String getColorStringArray(int color) {
        return Arrays.toString(new double[]{
                Color.red(color) / 255.0,
                Color.green(color) / 255.0,
                Color.blue(color) / 255.0,
                Color.alpha(color) / 255.0
        });
    }

    private static String loadRawResourceAsString(int resourceId) {
        //noinspection CharsetObjectCanBeUsed
        try (InputStream inputStream = Utils.getContext().getResources().openRawResource(resourceId);
             Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return scanner.next();
        } catch (IOException e) {
            throw new IllegalStateException("Could not load resource: " + resourceId);
        }
    }
}
