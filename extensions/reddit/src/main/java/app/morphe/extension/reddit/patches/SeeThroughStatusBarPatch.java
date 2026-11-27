/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5280
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;
import static app.morphe.extension.reddit.utils.ComposeUtils.pack;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackFirst;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackSecond;

import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
import app.morphe.extension.reddit.utils.ComposeUtils.Modifier;
import app.morphe.extension.reddit.utils.ComposeUtils.WindowInsets;
import app.morphe.extension.shared.Logger;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
public final class SeeThroughStatusBarPatch {

    /**
     * Draws a fade over the part of the feed that is behind the status bar.
     */
    private static final class FadeDraw implements Function1<ContentDrawScope, Object> {
        private static final FadeDraw INSTANCE = new FadeDraw();

        private static final int FADE_ALPHA = 235;

        /**
         * Higher values fade out closer to the top of the screen.
         */
        private static final double FADE_EXPONENT = 1.6;
        private static final int FADE_STEPS = 8;

        private final Paint paint = new Paint();

        private int shaderHeight;
        @ColorInt
        private int shaderColor;

        @Override
        public Object invoke(ContentDrawScope scope) {
            scope.patch_drawContent();

            try {
                final int height = getStatusBarHeight();
                if (height > 0) {
                    updateShader(height, (int) (appBarColor >>> 32));
                    scope.patch_getCanvas().patch_getNativeCanvas().drawRect(
                            0, 0, unpackFirst(scope.patch_getSize()), height, paint);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "FadeDraw failure", ex);
            }

            return null;
        }

        private void updateShader(int height, @ColorInt int color) {
            if (shaderHeight == height && shaderColor == color) {
                return;
            }

            int[] colors = new int[FADE_STEPS + 1];
            for (int i = 0; i <= FADE_STEPS; i++) {
                final double remaining = 1 - (double) i / FADE_STEPS;
                final int alpha = (int) Math.round(FADE_ALPHA * Math.pow(remaining, FADE_EXPONENT));
                colors[i] = (alpha << 24) | (color & 0x00FFFFFF);
            }

            paint.setShader(new LinearGradient(0, 0, 0, height, colors, null, Shader.TileMode.CLAMP));
            shaderHeight = height;
            shaderColor = color;
        }
    }

    /**
     * Draws the background of the app bar and of the status bar above it.
     * Unlike the original background, it fades out together with the app bar.
     */
    private static final class AppBarDraw implements Function1<ContentDrawScope, Object> {
        private static final AppBarDraw INSTANCE = new AppBarDraw();

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                // The menu button and the avatar have their own glass. With the tabs in the
                // top bar row, the top bar is drawn with the color of the page until the feed scrolls.
                final boolean fillTopTabsBar = GlassNavigationBarTopTabs.isShown()
                        && GlassNavigationBarTopTabs.isFeedOnScreenAtTop();
                if (fillTopTabsBar || !GlassNavigationBarPatch.isSearchButtonActive()) {
                    final int statusBarHeight = getStatusBarHeight();
                    final long size = scope.patch_getSize();

                    scope.patch_drawRoundRect(
                            fillTopTabsBar ? GlassNavigationBarPatch.getPackedPageColor() : appBarColor,
                            pack(0, -statusBarHeight),
                            pack(unpackFirst(size), unpackSecond(size) + statusBarHeight),
                            pack(0, 0)
                    );
                }
            } catch (Exception ex) {
                Logger.printException(() -> "AppBarDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Mask of everything except the alpha of a packed Compose sRGB color.
     */
    private static final long COLOR_WITHOUT_ALPHA_MASK = 0x00FFFFFFFFFFFFFFL;

    private static final boolean SEE_THROUGH_ENABLED = Settings.SEE_THROUGH_STATUS_BAR.get();

    private static boolean isSideLayout;

    /**
     * Packed Compose sRGB color, with the ARGB color in the upper 32 bits.
     */
    private static long appBarColor;

    @Nullable
    private static WindowInsets statusBarInsets;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static boolean isEnabled() {
        return SEE_THROUGH_ENABLED && !isSideLayout;
    }

    /**
     * @return If this patch is included and enabled, in any layout.
     */
    static boolean isSeeThroughEnabled() {
        return isPatchIncluded() && SEE_THROUGH_ENABLED;
    }

    private static int getStatusBarHeight() {
        return statusBarInsets == null ? 0 : statusBarInsets.patch_getTop();
    }

    /**
     * Injection point.
     * <p>
     * Called before all other hooks of the home screen.
     *
     * @param sideLayout If the side rail is shown instead of the bottom navigation bar.
     */
    public static void setSideLayout(boolean sideLayout) {
        isSideLayout = sideLayout;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the feed.
     * @param insets   Status bar insets.
     */
    public static Object getFeedModifier(Modifier modifier, WindowInsets insets) {
        if (!isEnabled()) {
            return modifier.patch_windowInsetsPadding(insets);
        }

        statusBarInsets = insets;
        return GlassNavigationBarPatch.getFeedFrostedModifier(
                (Modifier) modifier.patch_drawWithContent(FadeDraw.INSTANCE)
        );
    }

    /**
     * Injection point.
     */
    public static long getAppBarColor(long color) {
        if (!isEnabled()) {
            return color;
        }

        appBarColor = color;
        return color & COLOR_WITHOUT_ALPHA_MASK;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the app bar, before its graphics layer.
     */
    public static Object getAppBarLayerModifier(Modifier modifier) {
        try {
            if (isEnabled()) {
                return GlassNavigationBarPatch.getTopBarFrostedModifier(modifier);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getAppBarLayerModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the app bar content, which is below the status bar.
     */
    public static Object getAppBarModifier(Modifier modifier) {
        return isEnabled()
                ? modifier.patch_drawWithContent(AppBarDraw.INSTANCE)
                : modifier;
    }

    /**
     * Injection point.
     *
     * @param heightDp Space above the first post of the feed.
     */
    public static float getTopBarHeight(float heightDp) {
        return isEnabled()
                ? heightDp + getStatusBarHeight() / getDensity()
                : heightDp;
    }
}
