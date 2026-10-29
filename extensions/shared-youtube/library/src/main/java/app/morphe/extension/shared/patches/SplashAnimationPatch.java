/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3178
 * https://github.com/MorpheApp/morphe-patches/pull/4109
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
import java.util.LinkedHashMap;
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
        DISABLED(0, false),
        FPS_60_ONE_SECOND(1, false),
        FPS_60_TWO_SECOND(2, false),
        FPS_60_FIVE_SECOND(3, false),
        FPS_60_BLACK_AND_WHITE(4, true),
        FPS_30_ONE_SECOND(5, false),
        FPS_30_TWO_SECOND(6, false),
        FPS_30_FIVE_SECOND(7, false),
        FPS_30_BLACK_AND_WHITE(8, true),
        /**
         * YouTube 21.39+ Rive animation of a logo that comes into focus out of a colored glow.
         * Low memory devices cannot play it and get the Lottie animation of this style instead.
         */
        RIVE_GLOW(1, false),
        /**
         * YouTube 21.30+ Rive animation of the Morphe logo coming into focus out of a glow,
         * played in place of the Rive startup animation of the app.
         * Low memory devices get the Morphe animation of {@link #MORPHE_COLOR} instead.
         */
        RIVE_GLOW_MORPHE(1, false),
        /**
         * Another Morphe Glow animation, the same as {@link #RIVE_GLOW_MORPHE} except for the animation played.
         */
        RIVE_GLOW_MORPHE_2(1, false),
        /**
         * Morphe animation of the launcher icon, which replaces the Lottie animation of the app.
         */
        MORPHE_COLOR(1, false),
        /**
         * Morphe animation of the launcher icon in the foreground color of the theme.
         */
        MORPHE_MONOCHROME(1, true);
        // There exists a 10th JSON style used as the switch statement default,
        // but visually it is identical to 60fps one second.
        //
        // YT Music has a single animation and no styles, so only the styles shown in the
        // settings apply to it: disabled, color, black and white, and the Morphe styles.

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

        /**
         * If the animation is drawn in the foreground color of the theme, or in black and white.
         */
        public final boolean isMonochrome;

        SplashScreenAnimationStyle(int style, boolean isMonochrome) {
            this.style = style;
            this.isMonochrome = isMonochrome;
        }

        /**
         * If the style plays a Rive animation, which YouTube 21.30+ can play.
         */
        public boolean isRive() {
            return this == RIVE_GLOW || isRiveGlowMorphe();
        }

        /**
         * If the style plays a Morphe Rive animation in place of the Rive startup animation of the app.
         */
        public boolean isRiveGlowMorphe() {
            return this == RIVE_GLOW_MORPHE || this == RIVE_GLOW_MORPHE_2;
        }
    }

    /**
     * The colors of the mark, as they are written in the JSON of the branded animation.
     * A gradient holds its colors without an alpha value, and the rest of the file with one.
     */
    private static final String BRAND_PRIMARY_COLOR = "[0.117647,0.352941,0.658824,1]";
    private static final String BRAND_SECONDARY_COLOR = "[0,0.686275,0.682353,1]";
    private static final String BRAND_GRADIENT = "[0,0.117647,0.352941,0.658824,1,0,0.686275,0.682353]";

    /**
     * If the app plays the Rive animations, which YouTube 21.30+ does.
     */
    private static boolean isRiveIncluded() {
        return false; // Modified during patching.
    }

    /**
     * The default style, which is the Morphe glow animation if the app has the Rive animations.
     */
    public static SplashScreenAnimationStyle getDefaultStyle() {
        return isRiveIncluded()
                ? SplashScreenAnimationStyle.RIVE_GLOW_MORPHE
                : getDefaultLottieStyle();
    }

    /**
     * The default style that is not a Rive animation.
     */
    public static SplashScreenAnimationStyle getDefaultLottieStyle() {
        return CustomBrandingPatch.isPatchIncluded()
                ? SplashScreenAnimationStyle.MORPHE_COLOR
                : SplashScreenAnimationStyle.FPS_60_ONE_SECOND;
    }

    public static boolean isMonochrome() {
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get().isMonochrome;
    }

    /**
     * Plays the Morphe animation if the style asks for it,
     * in the color of the theme for the monochrome style.
     *
     * @return If a Morphe animation was played, and the original one should not be.
     */
    public static boolean setBrandedSplashAnimation(LottieAnimationView view) {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get();
        if (style != SplashScreenAnimationStyle.MORPHE_COLOR && style != SplashScreenAnimationStyle.MORPHE_MONOCHROME
                && !style.isRiveGlowMorphe()) {
            return false;
        }

        final int animation = CustomBrandingPatch.getStartupAnimation();
        if (animation == 0) {
            return false;
        }

        if (style.isMonochrome) {
            // A gradient of a single color is a flat fill, so the same file
            // also serves as the monochrome animation.
            final int color = ThemeUtils.getAppForegroundColor();
            final String flatColor = getColorStringArray(color);

            replaceColorsAndPlay(view, animation, Map.of(
                    BRAND_PRIMARY_COLOR, flatColor,
                    BRAND_SECONDARY_COLOR, flatColor,
                    BRAND_GRADIENT, getGradientStringArray(color)
            ), COLOR_KEYS);
        } else {
            view.patch_setAnimation(animation);
        }

        return true;
    }

    /**
     * A fixed color is written as the value of a property.
     */
    private static final String[] FIXED_COLOR_KEYS = { "\"k\":" };

    /**
     * A fixed color, and a color that animates, which is written as the value of one of its keyframes.
     */
    private static final String[] COLOR_KEYS = { "\"k\":", "\"s\":" };

    /**
     * Plays a Lottie animation with some of the colors of the file replaced.
     *
     * @param replacements The color of the file, as it is written in the JSON, and its replacement.
     */
    public static void setSplashAnimation(LottieAnimationView view, int resourceId,
                                          Map<String, Integer> replacements) {
        replaceColorsAndPlay(view, resourceId, getColorStringArrays(replacements), COLOR_KEYS);
    }

    /**
     * Plays a Lottie animation with some of the fixed colors of the file replaced.
     * The colors that animate are kept, so a shape that fades out of a replaced color still
     * starts with its original color.
     *
     * @param replacements The color of the file, as it is written in the JSON, and its replacement.
     */
    public static void setSplashAnimationFixedColors(LottieAnimationView view, int resourceId,
                                                     Map<String, Integer> replacements) {
        replaceColorsAndPlay(view, resourceId, getColorStringArrays(replacements), FIXED_COLOR_KEYS);
    }

    private static Map<String, String> getColorStringArrays(Map<String, Integer> replacements) {
        Map<String, String> colors = new LinkedHashMap<>();
        replacements.forEach((original, color) -> colors.put(original, getColorStringArray(color)));
        return colors;
    }

    private static void replaceColorsAndPlay(LottieAnimationView view, int resourceId,
                                             Map<String, String> replacements, String[] colorKeys) {
        String json = loadRawResourceAsString(resourceId);
        String replacement = json;

        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            final String original = entry.getKey();

            if (BaseSettings.DEBUG.get() && !json.contains(original)) {
                Logger.printException(() -> "Could not replace splash animation color: " + original);
            }

            for (String key : colorKeys) {
                replacement = replacement.replace(key + original, key + entry.getValue());
            }
        }

        // cacheKey is not needed since the animation will not be reused.
        view.patch_setAnimation(new ByteArrayInputStream(replacement.getBytes()), null);
    }

    private static String getGradientStringArray(int color) {
        final String stop = Color.red(color) / 255.0 + ", "
                + Color.green(color) / 255.0 + ", "
                + Color.blue(color) / 255.0;

        return "[0, " + stop + ", 1, " + stop + "]";
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
