/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import android.graphics.Color;

import androidx.annotation.Nullable;

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
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * The animation an app plays while it starts, shared by YouTube and YT Music.
 */
@SuppressWarnings("unused")
public class SplashAnimationPatch {

    public enum SplashScreenAnimationStyle {
        // 0 int style exists in target app as a fall through default, but its value is repurposed to be disabled.
        DISABLED(0),
        FPS_60_ONE_SECOND(1),
        FPS_60_TWO_SECOND(2),
        FPS_60_FIVE_SECOND(3),
        FPS_60_BLACK_AND_WHITE(4),
        FPS_30_ONE_SECOND(5),
        FPS_30_TWO_SECOND(6),
        FPS_30_FIVE_SECOND(7),
        FPS_30_BLACK_AND_WHITE(8);
        // There exists a 10th JSON style used as the switch statement default,
        // but visually it is identical to 60fps one second.
        //
        // YT Music has a single animation and no styles, so only the three styles
        // shown in the settings apply to it: disabled, color and black and white.

        @Nullable
        public static SplashScreenAnimationStyle styleFromOrdinal(int style) {
            // Alternatively can return using values()[style]
            for (SplashScreenAnimationStyle value : values()) {
                if (value.style == style) {
                    return value;
                }
            }

            return null;
        }

        public final int style;

        SplashScreenAnimationStyle(int style) {
            this.style = style;
        }
    }

    /**
     * Color the branded monochrome animation is drawn with in the file, as it is written in
     * the JSON. The mark never uses it, so nothing else of the file is replaced with it.
     */
    private static final String MONOCHROME_PLACEHOLDER_COLOR = "[1,0,1,1]";

    public static boolean isDisabled() {
        return SharedYouTubeSettings.SPLASH_SCREEN_ANIMATION_STYLE.get()
                == SplashScreenAnimationStyle.DISABLED;
    }

    public static boolean isMonochrome() {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_ANIMATION_STYLE.get();
        return style == SplashScreenAnimationStyle.FPS_30_BLACK_AND_WHITE
                || style == SplashScreenAnimationStyle.FPS_60_BLACK_AND_WHITE;
    }

    /**
     * Plays the animation of the branding icon, in the color of the theme if the style asks
     * for a monochrome animation.
     *
     * @return If a branded animation was played, and the original one should not be.
     */
    public static boolean setBrandedSplashAnimation(LottieAnimationView view) {
        final boolean monochrome = isMonochrome();
        final int animation = CustomBrandingPatch.getStartupAnimation(monochrome);
        if (animation == 0) {
            return false;
        }

        if (monochrome) {
            setSplashAnimation(view, animation, Map.of(
                    MONOCHROME_PLACEHOLDER_COLOR, ThemeUtils.getAppForegroundColor()
            ));
        } else {
            view.patch_setAnimation(animation);
        }

        return true;
    }

    /**
     * Plays a Lottie animation with some of the colors of the file replaced.
     *
     * @param replacements The color of the file, as it is written in the JSON, and its replacement.
     */
    public static void setSplashAnimation(LottieAnimationView view, int resourceId,
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
