/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.DEFAULT;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.DISABLED;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MINIMAL;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MODERN_1;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MODERN_2;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MODERN_3;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MODERN_4;
import static app.morphe.extension.youtube.settings.Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG;
import static app.morphe.extension.youtube.settings.Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG_PLAYBACK;
import static app.morphe.extension.youtube.settings.Settings.MINIPLAYER_DISABLE_HORIZONTAL_REPOSITION;

import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "SpellCheckingInspection"})
public final class MiniplayerPatch {

    /**
     * Mini player type. Null fields indicates to use the original un-patched value.
     */
    public enum MiniplayerType {
        /**
         * Disabled. When swiped down the miniplayer is immediately closed.
         */
        DISABLED(false, null),
        /** Unmodified type, and same as un-patched. */
        DEFAULT(null, null),
        MINIMAL(false, null),
        TABLET(true, null),
        MODERN_1(null, 1),
        MODERN_2(null, 2),
        MODERN_3(null, 3),
        /**
         * Works and is functional with 20.03+
         */
        MODERN_4(null, 4),
        /**
         * Half broken miniplayer, and in 20.02 and earlier is declared as type 4.
         */
        MODERN_5(null, 5);

        /**
         * Legacy tablet hook value.
         */
        @Nullable
        final Boolean legacyTabletOverride;

        /**
         * Modern player type used by YT.
         */
        @Nullable
        final Integer modernPlayerType;

        MiniplayerType(@Nullable Boolean legacyTabletOverride, @Nullable Integer modernPlayerType) {
            this.legacyTabletOverride = legacyTabletOverride;
            this.modernPlayerType = modernPlayerType;
        }

        public boolean isModern() {
            return modernPlayerType != null;
        }
    }

    private static final int MINIPLAYER_SIZE;
    private static boolean offScreenMiniplayerButtonPressed = false;
    private static int miniplayerOffscreenState = 0;
    private static final int horizontalPositionJump = 5;

    static {
        // YT appears to use the device screen dip width, plus an unknown fixed horizontal padding size.
        DisplayMetrics displayMetrics = Utils.getContext().getResources().getDisplayMetrics();
        final int deviceDipWidth = (int) (displayMetrics.widthPixels / displayMetrics.density);

        // YT seems to use a minimum height to calculate the minimum miniplayer width based on the video.
        // 170 seems to be the smallest that can be used and using less makes no difference.
        final int WIDTH_DIP_MIN = 170; // Seems to be the smallest that works.
        final int HORIZONTAL_PADDING_DIP = 15; // Estimated padding.
        // Round down to the nearest 5 pixels, to keep any error toasts easier to read.
        final int estimatedWidthDipMax = 5 * ((deviceDipWidth - HORIZONTAL_PADDING_DIP) / 5);
        // On some ultra-low-end devices the pixel width and density are the same number,
        // which causes the estimate to always give a value of 1.
        // Fix this by using a fixed size twice the minimum width.
        final int WIDTH_DIP_MAX = estimatedWidthDipMax <= WIDTH_DIP_MIN
                ? 2 * WIDTH_DIP_MIN
                : estimatedWidthDipMax;
        Logger.printDebug(() -> "Screen dip width: " + deviceDipWidth + " maxWidth: " + WIDTH_DIP_MAX);

        int dipWidth = Settings.MINIPLAYER_WIDTH_DIP.get();

        if (dipWidth < WIDTH_DIP_MIN || dipWidth > WIDTH_DIP_MAX) {
            Utils.showToastLong(str("morphe_miniplayer_width_dip_invalid_toast",
                    WIDTH_DIP_MIN, WIDTH_DIP_MAX));

            // Instead of resetting, clamp the size at the bounds.
            dipWidth = Math.max(WIDTH_DIP_MIN, Math.min(dipWidth, WIDTH_DIP_MAX));
            Settings.MINIPLAYER_WIDTH_DIP.save(dipWidth);
        }

        MINIPLAYER_SIZE = dipWidth;
    }

    private static final boolean DISABLE_RESUMING_MINIPLAYER =
            Settings.MINIPLAYER_DISABLE_RESUMING.get();

    private static final MiniplayerType CURRENT_TYPE = Settings.MINIPLAYER_TYPE.get();

    /**
     * Cannot turn off double tap with modern 2 or 3 with later targets,
     * as forcing it off breakings tapping the miniplayer.
     */
    private static final boolean DOUBLE_TAP_ACTION_ENABLED = true;


    private static final boolean DRAG_AND_DROP_ENABLED =
            CURRENT_TYPE.isModern() && !Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP.get();

    private static final boolean HIDE_OVERLAY_BUTTONS_ENABLED =
            Settings.MINIPLAYER_HIDE_OVERLAY_BUTTONS.get()
                    && Settings.MINIPLAYER_HIDE_OVERLAY_BUTTONS.isAvailable();

    private static final boolean MINIPLAYER_ROUNDED_CORNERS_ENABLED =
            CURRENT_TYPE.isModern() && !Settings.MINIPLAYER_DISABLE_ROUNDED_CORNERS.get();

    private static final boolean MINIPLAYER_HORIZONTAL_DRAG_ENABLED =
            DRAG_AND_DROP_ENABLED && !MINIPLAYER_DISABLE_HORIZONTAL_DRAG.get();

    private static final Map<Integer, String> MINIMAL_PLAYER_DRAWABLES = Map.of(
            ResourceUtils.getStringIdentifier("accessibility_pause"),
            "yt_fill_pause_vd_theme_24",
            ResourceUtils.getStringIdentifier("accessibility_play"),
            "yt_fill_play_arrow_vd_theme_24",
            ResourceUtils.getStringIdentifier("accessibility_replay"),
            "yt_outline_replay_arrow_vd_theme_24"
    );

    private static final int OPACITY_LEVEL;

    static {
        int opacity = Settings.MINIPLAYER_OPACITY.get();

        if (opacity < 0 || opacity > 100) {
            Utils.showToastLong(str("morphe_miniplayer_opacity_invalid_toast"));
            opacity = Settings.MINIPLAYER_OPACITY.resetToDefault();
        }

        OPACITY_LEVEL = (opacity * 255) / 100;
    }

    public static final class MiniplayerHorizontalDragAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.MINIPLAYER_TYPE.get().isModern()
                    && !Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.MINIPLAYER_TYPE,
                    Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP
            );
        }
    }

    public static final class MiniplayerHorizontalDragPlaybackAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.MINIPLAYER_TYPE.get().isModern()
                    && !Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP.get()
                    && !Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.MINIPLAYER_TYPE,
                    Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP,
                    Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG
            );
        }
    }

    public static final class MiniplayerHorizontalRepositioningAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.MINIPLAYER_TYPE.get().isModern()
                    && !Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP.get()
                    && !Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG.get();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.MINIPLAYER_TYPE,
                    Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP,
                    Settings.MINIPLAYER_DISABLE_HORIZONTAL_DRAG
            );
        }
    }


    public static final class MiniplayerHideOverlayButtonsAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            MiniplayerType type = Settings.MINIPLAYER_TYPE.get();
            return type == MODERN_4 || type == MODERN_3;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.MINIPLAYER_TYPE,
                    Settings.MINIPLAYER_DISABLE_DRAG_AND_DROP
            );
        }
    }

    public static final class MiniplayerAnyModernAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            MiniplayerType type = Settings.MINIPLAYER_TYPE.get();
            return type == MODERN_1 || type == MODERN_2 || type == MODERN_3 || type == MODERN_4;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.MINIPLAYER_TYPE);
        }
    }

    public static final class MiniplayerOverlayOpacityAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            MiniplayerType type = Settings.MINIPLAYER_TYPE.get();
            return type == MODERN_1;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.MINIPLAYER_TYPE);
        }
    }

    /**
     * Injection point.
     */
    public static boolean disableResumingStartupMiniPlayer(boolean original) {
        return !DISABLE_RESUMING_MINIPLAYER && original;
    }

    /**
     * Injection point.
     * <p>
     * Enables a handler that immediately closes the miniplayer when the video is minimized,
     * effectively disabling the miniplayer.
     */
    public static boolean getMiniplayerOnCloseHandler(boolean original) {
        return CURRENT_TYPE == DEFAULT
                ? original
                : CURRENT_TYPE == DISABLED;
    }

    /**
     * Injection point.
     */
    public static boolean getLegacyTabletMiniplayerOverride(boolean original) {
        Boolean isTablet = CURRENT_TYPE.legacyTabletOverride;
        return isTablet == null
                ? original
                : isTablet;
    }

    /**
     * Injection point.
     */
    public static boolean getModernMiniplayerOverride(boolean original) {
        return CURRENT_TYPE == DEFAULT
                ? original
                : CURRENT_TYPE.isModern();
    }

    /**
     * Injection point.
     */
    public static int getModernMiniplayerOverrideType(int original) {
        if (CURRENT_TYPE == MINIMAL) {
            // In newer app targets the minimal player can show the wrong icon if modern 4 is allowed.
            // Forcing to modern 1 seems to work.
            return Objects.requireNonNull(MODERN_1.modernPlayerType);
        }

        Integer modernValue = CURRENT_TYPE.modernPlayerType;
        return modernValue == null
                ? original
                : modernValue;
    }

    /**
     * Injection point.
     */
    public static void adjustMiniplayerOpacity(View view) {
        if (CURRENT_TYPE == MODERN_1) {
            if (view instanceof ImageView imageView) {
                imageView.setImageAlpha(OPACITY_LEVEL);
            } else {
                Logger.printException(() -> "Unknown miniplayer overlay view: " + view);
            }
        }
    }

    /**
     * Injection point.
     */
    public static boolean getModernFeatureFlagsActiveOverride(boolean original) {
        if (CURRENT_TYPE == DEFAULT) {
            return original;
        }

        return CURRENT_TYPE.isModern();
    }

    /**
     * Injection point.
     */
    public static boolean getMiniplayerDoubleTapAction(boolean original) {
        if (CURRENT_TYPE == DEFAULT) {
            return original;
        }

        return DOUBLE_TAP_ACTION_ENABLED;
    }

    /**
     * Injection point.
     */
    public static boolean getMiniplayerDragAndDrop(boolean original) {
        if (CURRENT_TYPE == DEFAULT) {
            return original;
        }

        return DRAG_AND_DROP_ENABLED;
    }

    /**
     * Injection point.
     */
    public static boolean getRoundedCorners(boolean original) {
        if (CURRENT_TYPE == DEFAULT) {
            return original;
        }

        return MINIPLAYER_ROUNDED_CORNERS_ENABLED;
    }

    /**
     * Injection point.
     */
    public static boolean getHorizontalDrag(boolean original) {
        if (CURRENT_TYPE == DEFAULT) {
            return original;
        }

        return MINIPLAYER_HORIZONTAL_DRAG_ENABLED;
    }

    /**
     * Injection point.
     */
    public static boolean pausePlaybackWithHorizontalDrag() {
        return MINIPLAYER_HORIZONTAL_DRAG_ENABLED && !MINIPLAYER_DISABLE_HORIZONTAL_DRAG_PLAYBACK.get();
    }

    /**
     * Injection point.
     * Check if the button to show the miniplayer from offscreen is pressed and skip
     * the code to change the miniplayer param offsets to prevent repositioning.
     */
    public static void enableOffScreenMiniplayerButtonPressed(MotionEvent motionEvent) {
        if (!MINIPLAYER_DISABLE_HORIZONTAL_REPOSITION.get()) {
            return;
        }

        if (miniplayerOffscreenState > 0 &&
                motionEvent.getAction() == MotionEvent.ACTION_UP &&
                motionEvent.getEventTime() - motionEvent.getDownTime() < 200) {
            offScreenMiniplayerButtonPressed = true;

            Utils.runOnMainThreadDelayed(
                    () -> offScreenMiniplayerButtonPressed = false,
                    1000
            );
        }
    }

    /**
     * Injection point.
     * Forcefully set the current params of miniplayer rect to the device offscreen offsets, only when the miniplayer is set
     * offscreen, in order to prevent miniplayer from being shown itself during the user's navigation across the app.
     */
    public static Rect blockOffscreenMiniplayerHorizontalReposition(Rect currentRect, Rect previousRect) {
        if (!MINIPLAYER_DISABLE_HORIZONTAL_REPOSITION.get()) {
            miniplayerOffscreenState = 0;
            return currentRect;
        }

        // Retrieve `displayMetrics` at runtime to ensure correct calculations for foldable devices.
        DisplayMetrics displayMetrics = Utils.getContext().getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        if (!offScreenMiniplayerButtonPressed) {
            int previousRectTop = previousRect.top;
            int previousRectLeft = previousRect.left;
            int originalWidth = currentRect.width();

            if (previousRectLeft != screenWidth || currentRect.left >= screenWidth) {
                // Offscreen params is forcefully set on the right side.
                if (previousRectLeft < 0 && previousRect.right == 0 && currentRect.right > 0) {
                    currentRect.left = -originalWidth;
                    currentRect.right = 0;
                    miniplayerOffscreenState = 1;
                }
            } else {
                // Offscreen params is forcefully set on the left side.
                currentRect.left = screenWidth;
                currentRect.right = screenWidth + originalWidth;
                miniplayerOffscreenState = 2;
            }

            // Offscreen params is forcefully set to preserve the vertical position.
            if (miniplayerOffscreenState > 0 && currentRect.top != previousRectTop) {
                currentRect.top = previousRectTop;
                currentRect.bottom = previousRect.bottom;
            }
        } else {
            if (miniplayerOffscreenState > 0) {
                // Button to show the miniplayer from its offscreen position is pressed.
                // Move the offscreen miniplayer of 5 pixels to the center of screen, in order to allow the
                // miniplayer animator to perform the transition to show it again.
                int originalWidth = currentRect.width();

                if (miniplayerOffscreenState == 1) {
                    currentRect.left = horizontalPositionJump;
                    currentRect.right = horizontalPositionJump + originalWidth;
                } else if (miniplayerOffscreenState == 2) {
                    currentRect.left = screenWidth - horizontalPositionJump;
                    currentRect.right = (screenWidth - horizontalPositionJump) + originalWidth;
                }

                miniplayerOffscreenState = 0;
            }
        }

        return currentRect;
    }

    /**
     * Injection point.
     */
    public static boolean getMaximizeAnimation(boolean original) {
        // This must be forced on if horizontal drag is enabled,
        // otherwise the UI has visual glitches when maximizing the miniplayer.
        if (MINIPLAYER_HORIZONTAL_DRAG_ENABLED) {
            return true;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static int getMiniplayerDefaultSize(int original) {
        if (CURRENT_TYPE.isModern()) {
            return MINIPLAYER_SIZE;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static boolean allowBoldIcons(boolean original) {
        if (CURRENT_TYPE == MINIMAL) {
            // Minimal player does not have the correct pause/play icon (it's too large).
            // Use the non-bold icons instead.
            return false;
        }

        return original;
    }

    /**
     * Injection point.
     * <p>
     * Fixes the fullscreen button tint when the minimal miniplayer type is selected.
     * The minimal type applies a theme where {@code ytOverlayButtonPrimary} resolves to gray
     * instead of white, making the fullscreen button appear gray.
     */
    public static void fixMinimalMiniplayerFullscreenButtonTint(View view) {
        if (CURRENT_TYPE != MINIMAL) return;

        if (view instanceof ImageView imageView) {
            imageView.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        }
    }

    /**
     * Injection point.
     */
    public static void hideMiniplayerExpandClose(View view) {
        Utils.hideViewByRemovingFromParentUnderCondition(HIDE_OVERLAY_BUTTONS_ENABLED, view);
    }

    /**
     * Injection point.
     */
    public static void hideMiniplayerActionButton(View view) {
        if (CURRENT_TYPE == MODERN_4) {
            Utils.hideViewByRemovingFromParentUnderCondition(HIDE_OVERLAY_BUTTONS_ENABLED, view);
        }
    }

    /**
     * Injection point.
     */
    public static void overrideMiniplayerActionButtonDrawable(ImageView view, int contentDescriptionId) {
        if (!VersionCheckPatch.IS_21_17_OR_GREATER || CURRENT_TYPE != MINIMAL) {
            return;
        }

        String drawableName = MINIMAL_PLAYER_DRAWABLES.get(contentDescriptionId);
        if (drawableName != null) {
            Drawable drawable = ResourceUtils.getDrawable(drawableName);
            if (drawable != null) {
                view.setImageDrawable(drawable);
            }
        }
    }
}
