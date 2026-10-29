/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 * https://github.com/MorpheApp/morphe-patches/pull/4135
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.theme;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.airbnb.lottie.LottieAnimationView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.CustomBrandingPatch;
import app.morphe.extension.shared.patches.SplashAnimationPatch;
import app.morphe.extension.shared.patches.SplashAnimationPatch.SplashScreenAnimationStyle;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.BaseThemePatch;
import app.morphe.extension.shared.theme.ThemeColorPatch;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ThemePatch extends BaseThemePatch {
    // Color constants used in relation with litho components.
    private static final int[] WHITE_VALUES = {
            0xFFFFFFFF, // Comments chip background.
            0xFFF9F9F9, // Music related results panel background.
            0xFAFFFFFF, // Video chapters list background.
    };

    private static final int[] DARK_VALUES = {
            0xFF282828, // Explore drawer background.
            0xFF212121, // Comments chip background.
            0xFF181818, // Music related results panel background.
            0xFF0F0F0F, // Comments chip background (new layout).
            0xFA212121, // Video chapters list background.
            0xFF222225, // Flyout sub-menu background.
            0xFF0E0E10, // Playlist content background.
            0xFF09090A, // Watch history chip background.
    };

    /**
     * Injection point.
     * <p>
     * Change the color of Litho components.
     * If the color of the component matches one of the values, return the background color.
     *
     * @param originalValue The original color value.
     * @return The new or original color value.
     */
    @ColorInt
    public static int getValue(@ColorInt int originalValue) {
        return processColorValue(originalValue, DARK_VALUES, WHITE_VALUES);
    }

    /**
     * Injection point.
     */
    public static boolean gradientLoadingScreenEnabled(boolean original) {
        return Settings.GRADIENT_LOADING_SCREEN.get();
    }

    /**
     * Injection point.
     */
    public static boolean useLotteLaunchSplashScreen(boolean original) {
        Logger.printDebug(() -> "Lottie splash screen flag: " + original);
        return true; // Force lottie animation view.
    }

    /**
     * The dark backdrop the glow animation is drawn on.
     */
    @ColorInt
    private static final int RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR = 0xFF09090A;

    /**
     * The backdrop color of the glow animation, as it is written in the Rive file:
     * a color keyframe (property key 88) followed by the ARGB color in little endian order.
     */
    private static final byte[] RIVE_GLOW_BACKGROUND_KEYFRAME = { 0x58, 0x0A, 0x09, 0x09, (byte) 0xFF };

    /**
     * The glow animation is made for a 440 x 956 phone screen, but the app shows only
     * a square in the middle of it, and the glow is cut off at the edges of the square.
     */
    private static final float RIVE_GLOW_ARTBOARD_WIDTH = 440;
    private static final float RIVE_GLOW_ARTBOARD_HEIGHT = 956;

    /**
     * The artboard size of the glow animation, as it is written in the Rive file:
     * the width (property key 7) and height (property key 8), each followed by a little endian float.
     */
    private static final byte[] RIVE_GLOW_ARTBOARD_SIZE = {
            0x07, 0x00, 0x00, (byte) 0xDC, 0x43,
            0x08, 0x00, 0x00, 0x6F, 0x44
    };

    private static final byte RIVE_PROPERTY_KEY_WIDTH = 7;
    private static final byte RIVE_PROPERTY_KEY_HEIGHT = 8;
    private static final byte RIVE_PROPERTY_KEY_ORIGIN_X = 11;
    private static final byte RIVE_PROPERTY_KEY_ORIGIN_Y = 12;

    /**
     * The timeline of the glow animation, as it is written in the Rive file: a linear animation
     * (type 31) named "Timeline 1", 150 frames long, that loops. Its keyed objects follow it.
     */
    private static final byte[] RIVE_GLOW_TIMELINE = {
            0x1F, 0x37, 0x0A, 'T', 'i', 'm', 'e', 'l', 'i', 'n', 'e', ' ', '1',
            0x39, (byte) 0x96, 0x01, 0x3B, 0x01, 0x00
    };

    /**
     * Keyframes added to the timeline of the glow animation. The animation starts with a glow
     * that covers the whole screen, and it fades in from the backdrop instead of appearing at once:
     * the artboard (keyed object type 25, object id 0) has its opacity (keyed property type 26,
     * property key 18) go from 0 at frame 0 to 1 at frame 20 (linear keyframes of type 30).
     */
    private static final byte[] RIVE_GLOW_FADE_IN_KEYFRAMES = {
            0x19, 0x33, 0x00, 0x00,
            0x1A, 0x35, 0x12, 0x00,
            0x1E, 0x44, 0x01, 0x46, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x1E, 0x43, 0x14, 0x44, 0x01, 0x46, 0x00, 0x00, (byte) 0x80, 0x3F, 0x00
    };

    /**
     * The key the app keeps the Rive file of a raw resource in memory with, followed by the resource id.
     */
    private static final String RIVE_RAW_RESOURCE_KEY_PREFIX = "raw_res_";

    /**
     * The color the glow animation is drawn on, or 0 if the animation is not shown.
     */
    @ColorInt
    private static volatile int riveGlowBackgroundColor;

    /**
     * The artboard size that fills the screen at the scale the app draws the glow animation with,
     * or 0 to keep the original size.
     */
    private static volatile float riveGlowArtboardWidth;
    private static volatile float riveGlowArtboardHeight;

    /**
     * How long the system splash screen is kept at most while the glow animation loads.
     */
    private static final long RIVE_SPLASH_SCREEN_TIMEOUT_MILLISECONDS = 1500;

    /**
     * How long the system splash screen fades out when the glow animation has drawn.
     */
    private static final long RIVE_SPLASH_SCREEN_FADE_OUT_MILLISECONDS = 300;

    /**
     * Removes the system splash screen that is kept while the glow animation loads,
     * or null if it is not kept. Used on the main thread.
     */
    @Nullable
    private static volatile Runnable riveSplashScreenRemover;

    /**
     * How many frames the glow animation draws before the device is known to support it.
     */
    private static final int RIVE_SPLASH_SCREEN_SUPPORTED_FRAMES = 30;

    /**
     * Values of {@link Settings#RIVE_SPLASH_SCREEN_STATE}. The state is set to failed before the
     * animation starts, and set to played when the animation has drawn. If the app closes in
     * between, it stays failed and the animation is turned off on the next launch.
     * After the animation has played once, it is not checked again.
     */
    private static final int RIVE_SPLASH_SCREEN_STATE_NOT_PLAYED = 0;
    private static final int RIVE_SPLASH_SCREEN_STATE_PLAYED = 1;
    private static final int RIVE_SPLASH_SCREEN_STATE_FAILED = -1;

    /**
     * Frames the glow animation still has to draw before the device is known to support it,
     * or 0 if the animation is not starting.
     */
    private static volatile int riveSplashScreenFramesUntilSupported;

    /**
     * If the activity has not started since the glow animation was set up.
     */
    private static volatile boolean riveSplashScreenFirstStart;

    /**
     * How long the Morphe Glow animation plays from its first frame until the splash screen closes.
     * The animation ends after 1.3 seconds, and the timeout of the app would keep it longer.
     */
    private static final long RIVE_GLOW_MORPHE_DURATION_MILLISECONDS = 1400;

    /**
     * Closes the splash screen when the Morphe Glow animation has played,
     * or null if the animation is not waiting for its first frame.
     */
    @Nullable
    private static volatile Runnable riveGlowMorpheSplashScreenCloser;

    /**
     * The Morphe Glow 1 animation for the dark theme, which is played in place of the startup
     * animation of the app. It has the same backdrop and artboard size as the glow animation, so it is changed the same
     * way, and it fades in by itself.
     */
    private static final String RIVE_GLOW_MORPHE_1_DARK_RESOURCE = "morphe_startup_animation_glow_1_dark";

    /**
     * The Morphe Glow 1 animation for the light theme, with the same glow colors as the dark theme
     * animation but less bright.
     */
    private static final String RIVE_GLOW_MORPHE_1_LIGHT_RESOURCE = "morphe_startup_animation_glow_1_light";

    /**
     * The Morphe Glow 1 animation for the dark theme with the Morphe play icon,
     * played if the launcher icon is the play icon.
     */
    private static final String RIVE_GLOW_MORPHE_1_PLAY_DARK_RESOURCE = "morphe_startup_animation_glow_1_play_dark";

    /**
     * The Morphe Glow 1 play animation for the light theme, with a less bright glow.
     */
    private static final String RIVE_GLOW_MORPHE_1_PLAY_LIGHT_RESOURCE = "morphe_startup_animation_glow_1_play_light";

    /**
     * The Morphe Glow 2 animation for the dark theme, which is played in place of the startup animation of the app.
     */
    private static final String RIVE_GLOW_MORPHE_2_DARK_RESOURCE = "morphe_startup_animation_glow_2_dark";

    /**
     * The Morphe Glow 2 animation for the light theme, with a less bright glow.
     */
    private static final String RIVE_GLOW_MORPHE_2_LIGHT_RESOURCE = "morphe_startup_animation_glow_2_light";

    /**
     * The Morphe Glow 2 animation for the dark theme with the Morphe play icon,
     * played if the launcher icon is the play icon.
     */
    private static final String RIVE_GLOW_MORPHE_2_PLAY_DARK_RESOURCE = "morphe_startup_animation_glow_2_play_dark";

    /**
     * The Morphe Glow 2 play animation for the light theme, with a less bright glow.
     */
    private static final String RIVE_GLOW_MORPHE_2_PLAY_LIGHT_RESOURCE = "morphe_startup_animation_glow_2_play_light";

    /**
     * The Morphe Glow 2 animations are made for the square view, and have no backdrop.
     */
    private static final float RIVE_GLOW_MORPHE_2_ARTBOARD_WIDTH = 432;
    private static final float RIVE_GLOW_MORPHE_2_ARTBOARD_HEIGHT = 432;

    /**
     * The artboard size of the Morphe Glow 2 animations, as it is written in the Rive file.
     */
    private static final byte[] RIVE_GLOW_MORPHE_2_ARTBOARD_SIZE = {
            0x07, 0x00, 0x00, (byte) 0xD8, 0x43,
            0x08, 0x00, 0x00, (byte) 0xD8, 0x43
    };

    private static boolean riveSplashScreenCrashChecked;

    private static boolean isRiveSplashScreenEnabled() {
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get().isRive();
    }

    /**
     * If the glow animation of the app is played. Otherwise, the Morphe Glow styles
     * play their own animation in place of the startup animation of the app.
     */
    private static boolean isRiveGlowStyle() {
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() == SplashScreenAnimationStyle.RIVE_GLOW;
    }

    /**
     * If the animation played has the backdrop and artboard size of the glow animation of the app:
     * the glow animation, or the Morphe Glow 1 animation.
     */
    private static boolean isRiveGlowLayout() {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get();
        return style == SplashScreenAnimationStyle.RIVE_GLOW || style == SplashScreenAnimationStyle.RIVE_GLOW_MORPHE;
    }

    /**
     * Some devices crash when drawing the glow animation, because the Rive renderer
     * uses GPU features they do not have. If the app closed before the animation could draw
     * the last time it started, the animation is turned off. Checked once when the app starts,
     * before this launch shows the animation.
     */
    private static synchronized void checkRiveSplashScreenCrash() {
        if (riveSplashScreenCrashChecked) {
            return;
        }
        riveSplashScreenCrashChecked = true;

        if (Settings.RIVE_SPLASH_SCREEN_STATE.get() == RIVE_SPLASH_SCREEN_STATE_FAILED) {
            Logger.printInfo(() -> "App closed before the glow splash screen could draw, changing splash screen style");
            Settings.RIVE_SPLASH_SCREEN_STATE.save(RIVE_SPLASH_SCREEN_STATE_NOT_PLAYED);
            // The default style can be a Rive animation.
            SharedYouTubeSettings.SPLASH_SCREEN_STYLE.save(SplashAnimationPatch.getDefaultLottieStyle());
            Utils.showToastLong(str("morphe_splash_screen_style_glow_not_supported"));
        }
    }

    /**
     * The Rive file of the splash screen: the glow animation of the app for the glow style,
     * and the startup animation of the light or the dark theme for the Morphe Glow styles.
     */
    private static boolean isRiveSplashScreenFile(int resourceId) {
        if (isRiveGlowStyle()) {
            return resourceId == ResourceUtils.getIdentifier(ResourceType.RAW, "rive_phantom");
        }

        return resourceId == ResourceUtils.getIdentifier(ResourceType.RAW, "rive_startup_animation_light")
                || resourceId == ResourceUtils.getIdentifier(ResourceType.RAW, "rive_startup_animation_dark");
    }

    /**
     * Injection point.
     */
    public static boolean useRiveSplashScreen(boolean original) {
        try {
            if (isRiveSplashScreenEnabled()) {
                checkRiveSplashScreenCrash();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "useRiveSplashScreen failure", ex);
        }

        return isRiveSplashScreenEnabled();
    }

    /**
     * Injection point.
     * <p>
     * 21.39+ plays the glow animation if this is on, and otherwise the startup animation
     * of the light or the dark theme.
     */
    public static boolean useRiveGlowSplashScreen(boolean original) {
        return useRiveSplashScreen(original) && isRiveGlowStyle();
    }

    /**
     * The background color the system drew the splash screen of this launch with.
     *
     * @return The color, or 0 if it is not a color.
     */
    @ColorInt
    private static int getSplashScreenBackgroundColor(Activity activity) throws PackageManager.NameNotFoundException {
        PackageManager packageManager = activity.getPackageManager();

        int themeId = ThemeColorPatch.getLaunchSplashScreenTheme();
        if (themeId == 0) {
            themeId = packageManager.getActivityInfo(activity.getComponentName(), 0).getThemeResource();
        }

        // The system resolves the theme with the configuration of the device,
        // and not with the theme color variant that the contexts of the app use.
        Resources.Theme theme = packageManager
                .getResourcesForApplication(activity.getApplicationInfo())
                .newTheme();
        theme.applyStyle(themeId, true);

        // Android 12+ uses the splash screen background unless it is transparent,
        // and otherwise the window background.
        final int[] attributes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new int[]{android.R.attr.windowBackground, android.R.attr.windowSplashScreenBackground}
                : new int[]{android.R.attr.windowBackground};

        // TypedArray is AutoCloseable only on Android 12+, so it is recycled in finally.
        //noinspection resource
        TypedArray array = theme.obtainStyledAttributes(attributes);
        try {
            for (int i = attributes.length - 1; i >= 0; i--) {
                final int type = array.getType(i);
                if (type >= TypedValue.TYPE_FIRST_COLOR_INT && type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    final int color = array.getColor(i, 0);
                    if (Color.alpha(color) != 0) {
                        return color;
                    }
                }
            }
            return 0;
        } finally {
            array.recycle();
        }
    }

    /**
     * Injection point.
     *
     * @param animationView The view that the Rive animation is drawn in.
     */
    public static void setRiveSplashScreenBackground(View animationView) {
        try {
            if (!isRiveSplashScreenEnabled() || !(animationView.getParent() instanceof View parent)) {
                return;
            }

            Context context = animationView.getContext();
            while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) {
                context = wrapper.getBaseContext();
            }

            // The glow animation is drawn on the color of the splash screen, so it continues
            // it. The Rive file is changed to use the color when it is read.
            int color = context instanceof Activity activity
                    ? getSplashScreenBackgroundColor(activity)
                    : 0;
            if (Color.alpha(color) != 0xFF) {
                Logger.printDebug(() -> "Splash screen background is not an opaque color");
                color = RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR;
            }

            // The app draws the artboard to cover a square view, so the width of the artboard
            // fits the square. The view is changed to fill the screen, and the artboard is
            // given the shape of the screen at that same scale.
            float artboardWidth = 0;
            float artboardHeight = 0;
            ViewGroup.LayoutParams params = animationView.getLayoutParams();
            if (context instanceof Activity activity && params.width > 0) {
                final float originalArtboardWidth = isRiveGlowLayout()
                        ? RIVE_GLOW_ARTBOARD_WIDTH
                        : RIVE_GLOW_MORPHE_2_ARTBOARD_WIDTH;
                final float artboardUnitsPerPixel = originalArtboardWidth / params.width;
                Rect windowBounds = getWindowBounds(activity);
                artboardWidth = windowBounds.width() * artboardUnitsPerPixel;
                artboardHeight = windowBounds.height() * artboardUnitsPerPixel;

                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                animationView.setLayoutParams(params);
            } else {
                Logger.printDebug(() -> "Glow animation view is not a fixed size");
            }

            riveGlowArtboardWidth = artboardWidth;
            riveGlowArtboardHeight = artboardHeight;
            riveGlowBackgroundColor = color;
            parent.setBackgroundColor(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context instanceof Activity activity) {
                keepSplashScreenUntilRiveFrame(activity);
            }

            // Set to played when the animation has drawn, and back to not played if the app is stopped first.
            if (Settings.RIVE_SPLASH_SCREEN_STATE.get() != RIVE_SPLASH_SCREEN_STATE_PLAYED) {
                Settings.RIVE_SPLASH_SCREEN_STATE.save(RIVE_SPLASH_SCREEN_STATE_FAILED);
                riveSplashScreenFramesUntilSupported = RIVE_SPLASH_SCREEN_SUPPORTED_FRAMES;
            }
            riveSplashScreenFirstStart = true;
        } catch (Exception ex) {
            Logger.printException(() -> "setRiveSplashScreenBackground failure", ex);
        }
    }

    /**
     * The system removes its splash screen when the app has drawn, which is before
     * the glow animation has loaded. The splash screen is instead kept until the
     * animation has drawn, and then it fades out while the animation fades in.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static void keepSplashScreenUntilRiveFrame(Activity activity) {
        activity.getSplashScreen().setOnExitAnimationListener(splashScreenView -> {
            riveSplashScreenRemover = () -> splashScreenView.animate()
                    .alpha(0)
                    .setDuration(RIVE_SPLASH_SCREEN_FADE_OUT_MILLISECONDS)
                    .withEndAction(splashScreenView::remove);

            // In case the animation never draws.
            Utils.runOnMainThreadDelayed(ThemePatch::removeRiveSplashScreen,
                    RIVE_SPLASH_SCREEN_TIMEOUT_MILLISECONDS);
        });
    }

    private static void removeRiveSplashScreen() {
        Runnable remover = riveSplashScreenRemover;
        if (remover != null) {
            riveSplashScreenRemover = null;
            remover.run();
        }
    }

    /**
     * Injection point.
     * <p>
     * Called for each frame the Rive renderer draws.
     */
    public static void onRiveFrameAvailable() {
        try {
            if (riveSplashScreenRemover != null) {
                Utils.runOnMainThreadNowOrLater(ThemePatch::removeRiveSplashScreen);
            }

            Runnable closer = riveGlowMorpheSplashScreenCloser;
            if (closer != null) {
                riveGlowMorpheSplashScreenCloser = null;
                Utils.runOnMainThreadDelayed(closer, RIVE_GLOW_MORPHE_DURATION_MILLISECONDS);
            }

            final int framesUntilSupported = riveSplashScreenFramesUntilSupported;
            if (framesUntilSupported == 1) {
                riveSplashScreenFramesUntilSupported = 0;
                Settings.RIVE_SPLASH_SCREEN_STATE.save(RIVE_SPLASH_SCREEN_STATE_PLAYED);
            } else if (framesUntilSupported > 1) {
                riveSplashScreenFramesUntilSupported = framesUntilSupported - 1;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onRiveFrameAvailable failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * The glow animation is not drawn while the app is stopped, so it cannot crash the app.
     */
    public static void onSplashScreenStop() {
        try {
            if (riveSplashScreenFramesUntilSupported > 0) {
                riveSplashScreenFramesUntilSupported = 0;
                Settings.RIVE_SPLASH_SCREEN_STATE.save(RIVE_SPLASH_SCREEN_STATE_NOT_PLAYED);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSplashScreenStop failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * A home feed that is shown quickly closes the glow animation before it has drawn
     * anything, so on the first start the timeout of the app closes it instead.
     * The Morphe Glow animation is shorter, and closes when it has played.
     * Stopping the app cancels the timeout, and then the home feed closes it as usual.
     *
     * @param splashScreen       Removes the splash screen when run.
     * @param riveAnimationShown If the app shows a Rive animation.
     * @return If the home feed does not close the animation.
     */
    public static boolean skipSplashScreenCloseOnHomeFeed(Runnable splashScreen, boolean riveAnimationShown) {
        try {
            if (riveAnimationShown && riveSplashScreenFirstStart) {
                riveSplashScreenFirstStart = false;

                if (SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get().isRiveGlowMorphe()) {
                    riveGlowMorpheSplashScreenCloser = splashScreen;
                }
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "skipSplashScreenCloseOnHomeFeed failure", ex);
        }
        return false;
    }

    private static Rect getWindowBounds(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        }

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    /**
     * Injection point.
     * <p>
     * The app keeps the Rive file of a raw resource in memory, and the key of the glow animation
     * is changed for each background color, artboard size and logo. Otherwise, a file changed for
     * an earlier launch would be used.
     *
     * @param key The key of a raw resource, which is the prefix followed by the resource id.
     * @return The key to keep the Rive file in memory with.
     */
    public static String getRiveRawResourceKey(String key) {
        try {
            final int backgroundColor = riveGlowBackgroundColor;
            if (backgroundColor != 0
                    && key.startsWith(RIVE_RAW_RESOURCE_KEY_PREFIX)
                    && isRiveSplashScreenFile(Integer.parseInt(key.substring(RIVE_RAW_RESOURCE_KEY_PREFIX.length())))) {
                return key + "_" + Integer.toHexString(backgroundColor)
                        + "_" + riveGlowArtboardWidth + "x" + riveGlowArtboardHeight
                        + "_" + SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get().name()
                        + (CustomBrandingPatch.iconIsPlay() ? "_play" : "");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getRiveRawResourceKey failure", ex);
        }

        return key;
    }

    /**
     * Injection point.
     * <p>
     * Called when the app reads the Rive file of a raw resource it has not cached yet.
     * The splash screen reads its file after the background and size are set.
     * The Morphe Glow styles play their own animation in place of the startup animation of the app.
     *
     * @param fileBytes  The Rive file of a raw resource, or null if it could not be read.
     * @param resourceId The raw resource the file was read from.
     * @return The Rive file to play.
     */
    @Nullable
    public static byte[] getRiveFile(@Nullable byte[] fileBytes, int resourceId) {
        try {
            final int backgroundColor = riveGlowBackgroundColor;
            if (fileBytes == null || backgroundColor == 0 || !isRiveSplashScreenFile(resourceId)) {
                return fileBytes;
            }

            final float artboardWidth = riveGlowArtboardWidth;
            final float artboardHeight = riveGlowArtboardHeight;
            final boolean resizeArtboard = artboardWidth > 0 && artboardHeight > 0;

            if (!isRiveGlowStyle()) {
                // The app plays the startup animation of the light theme in the light theme.
                final boolean lightTheme = resourceId
                        == ResourceUtils.getIdentifier(ResourceType.RAW, "rive_startup_animation_light");

                if (!isRiveGlowLayout()) {
                    // The Morphe Glow 2 animations have no backdrop, and fade in by themselves.
                    final String resourceName;
                    if (CustomBrandingPatch.iconIsPlay()) {
                        resourceName = lightTheme
                                ? RIVE_GLOW_MORPHE_2_PLAY_LIGHT_RESOURCE
                                : RIVE_GLOW_MORPHE_2_PLAY_DARK_RESOURCE;
                    } else {
                        resourceName = lightTheme
                                ? RIVE_GLOW_MORPHE_2_LIGHT_RESOURCE
                                : RIVE_GLOW_MORPHE_2_DARK_RESOURCE;
                    }
                    fileBytes = readRawResource(ResourceUtils.getIdentifier(ResourceType.RAW, resourceName));
                    return resizeArtboard
                            ? setRiveArtboardSize(fileBytes, RIVE_GLOW_MORPHE_2_ARTBOARD_SIZE,
                            RIVE_GLOW_MORPHE_2_ARTBOARD_WIDTH, RIVE_GLOW_MORPHE_2_ARTBOARD_HEIGHT,
                            artboardWidth, artboardHeight)
                            : fileBytes;
                }

                final String resourceName;
                if (CustomBrandingPatch.iconIsPlay()) {
                    resourceName = lightTheme
                            ? RIVE_GLOW_MORPHE_1_PLAY_LIGHT_RESOURCE
                            : RIVE_GLOW_MORPHE_1_PLAY_DARK_RESOURCE;
                } else {
                    resourceName = lightTheme
                            ? RIVE_GLOW_MORPHE_1_LIGHT_RESOURCE
                            : RIVE_GLOW_MORPHE_1_DARK_RESOURCE;
                }
                fileBytes = readRawResource(ResourceUtils.getIdentifier(ResourceType.RAW, resourceName));
            }

            if (backgroundColor != RIVE_GLOW_ORIGINAL_BACKGROUND_COLOR) {
                setRiveGlowBackgroundColor(fileBytes, backgroundColor);
            }

            if (resizeArtboard) {
                fileBytes = setRiveArtboardSize(fileBytes, RIVE_GLOW_ARTBOARD_SIZE,
                        RIVE_GLOW_ARTBOARD_WIDTH, RIVE_GLOW_ARTBOARD_HEIGHT, artboardWidth, artboardHeight);
            }

            // The Morphe Glow 1 animation fades in by itself.
            if (!isRiveGlowStyle()) {
                return fileBytes;
            }

            final int timelineIndex = indexOf(fileBytes, RIVE_GLOW_TIMELINE);
            if (timelineIndex < 0) {
                Logger.printException(() -> "Could not find the timeline of the glow animation");
                return fileBytes;
            }

            return replaceBytes(fileBytes, timelineIndex + RIVE_GLOW_TIMELINE.length, 0,
                    RIVE_GLOW_FADE_IN_KEYFRAMES);
        } catch (Exception ex) {
            Logger.printException(() -> "getRiveFile failure", ex);
        }

        return fileBytes;
    }

    private static byte[] readRawResource(int resourceId) throws IOException {
        try (InputStream input = Utils.getContext().getResources().openRawResource(resourceId)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private static void setRiveGlowBackgroundColor(byte[] fileBytes, @ColorInt int color) {
        final int index = indexOf(fileBytes, RIVE_GLOW_BACKGROUND_KEYFRAME);
        if (index < 0) {
            Logger.printException(() -> "Could not find the background color of the glow animation");
            return;
        }

        // Replace the color after the property key.
        ByteBuffer.wrap(fileBytes, index + 1, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(color);
    }

    /**
     * @param sizeBytes      The width and height of the artboard, as they are written in the Rive file.
     * @param originalWidth  The width of the artboard in the Rive file.
     * @param originalHeight The height of the artboard in the Rive file.
     */
    private static byte[] setRiveArtboardSize(byte[] fileBytes, byte[] sizeBytes,
                                              float originalWidth, float originalHeight,
                                              float width, float height) {
        final int index = indexOf(fileBytes, sizeBytes);
        if (index < 0) {
            Logger.printException(() -> "Could not find the artboard size of the Rive animation");
            return fileBytes;
        }

        // The content is drawn from the origin of the artboard, which is a fraction of its size.
        // The origin keeps the content in the middle of the resized artboard.
        final float originX = (width - originalWidth) / 2 / width;
        final float originY = (height - originalHeight) / 2 / height;

        // Each property is a one byte property key followed by a float.
        byte[] artboardProperties = ByteBuffer.allocate(4 * (1 + Float.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(RIVE_PROPERTY_KEY_WIDTH).putFloat(width)
                .put(RIVE_PROPERTY_KEY_HEIGHT).putFloat(height)
                .put(RIVE_PROPERTY_KEY_ORIGIN_X).putFloat(originX)
                .put(RIVE_PROPERTY_KEY_ORIGIN_Y).putFloat(originY)
                .array();

        return replaceBytes(fileBytes, index, sizeBytes.length, artboardProperties);
    }

    private static byte[] replaceBytes(byte[] array, int index, int length, byte[] replacement) {
        final int end = index + length;
        return ByteBuffer.allocate(array.length - length + replacement.length)
                .put(array, 0, index)
                .put(replacement)
                .put(array, end, array.length - end)
                .array();
    }

    private static int indexOf(byte[] array, byte[] target) {
        outer:
        for (int i = 0, last = array.length - target.length; i <= last; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Injection point.
     * Modern Lottie style animation.
     */
    public static void setSplashAnimationLottie(LottieAnimationView view, int resourceId) {
        try {
            // The Morphe styles replace the YouTube logo animation with the Morphe one.
            if (SplashAnimationPatch.setBrandedSplashAnimation(view)) {
                return;
            }

            if (!SeekbarColorPatch.isCustomSeekbarColorEnabled()
                    // Black and white animations cannot use color replacements.
                    || SplashAnimationPatch.isMonochrome()) {
                view.patch_setAnimation(resourceId);
                return;
            }

            // The logo animates from the same red the progress bar is drawn with,
            // so only the fixed colors of the progress bar are replaced.
            SplashAnimationPatch.setSplashAnimationFixedColors(view, resourceId, Map.of(
                    "[1,0,0.2,1]", SeekbarColorPatch.getSeekbarColor(),
                    "[1,0.152941176471,0.56862745098,1]", SeekbarColorPatch.getSeekbarAccentColor()
            ));
        } catch (Exception ex) {
            Logger.printException(() -> "setSplashAnimationLottie failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static boolean showSplashScreen(boolean original) {
        return SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() != SplashScreenAnimationStyle.DISABLED && original;
    }

    /**
     * Injection point.
     */
    public static int showSplashScreen(int i, int i2) {
        if (SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get() != SplashScreenAnimationStyle.DISABLED || i != i2) {
            return i;
        }
        return i - 1;
    }

    /**
     * Injection point.
     */
    public static int getLoadingScreenType(int original) {
        SplashScreenAnimationStyle style = SharedYouTubeSettings.SPLASH_SCREEN_STYLE.get();

        if (style == SplashScreenAnimationStyle.DISABLED) {
            return original;
        }

        final int replacement = style.style;
        if (original != replacement) {
            Logger.printDebug(() -> "Overriding splash screen style from: "
                    + SplashScreenAnimationStyle.styleFromOrdinal(original) + " to: " + style);
        }

        return replacement;
    }

    /**
     * Injection point.
     */
    public static void overrideSpinnerPopupBackground(View view) {
        try {
            if (view instanceof Spinner spinner) {
                int backgroundColor = ThemeUtils.getAppBackgroundColor();
                spinner.setPopupBackgroundDrawable(new ColorDrawable(backgroundColor));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "overrideSpinnerPopupBackground failure", ex);
        }
    }
}
