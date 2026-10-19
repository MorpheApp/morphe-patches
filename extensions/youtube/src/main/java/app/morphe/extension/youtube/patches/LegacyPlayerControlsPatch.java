/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.spans.MetapanelTextColorFilter;
import app.morphe.extension.youtube.settings.Settings;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@SuppressWarnings("unused")
public class LegacyPlayerControlsPatch {

    public static final class RestoreOldPlayerButtonsAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return VersionCheckPatch.IS_20_31_OR_GREATER && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");
        }
    }

    public static final boolean RESTORE_OLD_PLAYER_BUTTONS =
            Settings.RESTORE_OLD_PLAYER_BUTTONS.get()
                    || !VersionCheckPatch.IS_20_31_OR_GREATER
                    || SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    /**
     * Injection point.
     */
    public static boolean useNullBottomGradient() {
        return RESTORE_OLD_PLAYER_BUTTONS;
    }

    /**
     * Injection point.
     */
    public static void hideBottomGradientScrim(ImageView bottomGradientScrim) {
        if (!RESTORE_OLD_PLAYER_BUTTONS) {
            return;
        }
        if (bottomGradientScrim != null) {
            Utils.runOnMainThread(() -> {
                bottomGradientScrim.setImageAlpha(0);
                bottomGradientScrim.setVisibility(View.GONE);
            });
        }
    }

    /**
     * Injection point.
     */
    public static boolean forcePlayerSeekbar(boolean original) {
        if (!original) {
            Logger.printDebug(() -> "Player seekbar feature flag is off");
        }
        return true;
    }

    /**
     * The value the app has for the new player overlays flag, before it is forced off.
     */
    private static volatile boolean newPlayerOverlaysOriginal;

    /**
     * Injection point.
     */
    public static boolean disableNewPlayerOverlays(boolean original) {
        newPlayerOverlaysOriginal = original;
        return false;
    }

    /**
     * Injection point.
     * Called right after the flag is read, so the value is the one just stored.
     */
    public static boolean getOriginalNewPlayerOverlays(boolean overridden) {
        return newPlayerOverlaysOriginal;
    }

    /**
     * Injection point.
     * <p>
     * The elevated player shows the video metadata over the ambient mode colors of the player,
     * and the light theme text can not be read over the dark colors, so the elevated player is turned off
     * (https://github.com/MorpheApp/morphe-patches/issues/3703).
     */
    public static boolean useElevatedPlayer(boolean original) {
        if (original) {
            elevatedPlayerTurnedOff = true;
        }
        return false;
    }

    /**
     * If the elevated player is on for the account, but it was turned off.
     */
    private static volatile boolean elevatedPlayerTurnedOff;

    /**
     * If the elevated player is on for the account, YouTube can send the video metadata panel
     * with the dark theme text colors, which are drawn over the dark colors of the elevated player.
     * The elevated player is turned off, so with the light theme the text is drawn over the light
     * background of the watch page and can not be read
     * (https://github.com/MorpheApp/morphe-patches/issues/3809).
     *
     * @return If the dark theme text colors of the video metadata panel must be replaced.
     */
    public static boolean fixMetapanelTextColors() {
        return elevatedPlayerTurnedOff && !Utils.isDarkModeEnabled();
    }

    /**
     * Images of the video metadata panel.
     */
    private static final Map<Object, Boolean> metapanelImages =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Image whose tint is being read by the current thread.
     */
    private static final ThreadLocal<Object> tintImage = new ThreadLocal<>();

    /**
     * Injection point.
     * Called when an image of the Elements templates is prepared.
     */
    public static void registerImage(Object conversionContext, @Nullable Object image) {
        if (image != null && conversionContext instanceof ContextInterface contextInterface
                && MetapanelTextColorFilter.isMetapanelIdentifier(contextInterface.patch_getIdentifier())) {
            metapanelImages.put(image, Boolean.TRUE);
        }
    }

    /**
     * Injection point.
     * Called before the tint color of an image of the Elements templates is read.
     */
    public static void setTintImage(Object image) {
        tintImage.set(image);
    }

    /**
     * Injection point.
     * <p>
     * The icons of the video metadata panel can have the dark theme tint,
     * as the text ({@link #fixMetapanelTextColors()}).
     *
     * @return The tint color of an image of the Elements templates.
     */
    public static int getImageTintColor(int color) {
        final Object image = tintImage.get();
        tintImage.remove();
        try {
            if (color == 0 || image == null || !MetapanelTextColorFilter.isLightColor(color)
                    || !fixMetapanelTextColors() || !metapanelImages.containsKey(image)) {
                return color;
            }

            return MetapanelTextColorFilter.getLightThemeTextColor(color);
        } catch (Exception ex) {
            Logger.printException(() -> "getImageTintColor failure", ex);
            return color;
        }
    }

    /**
     * Max height of the dots of a page indicator.
     */
    private static final float PAGE_INDICATOR_MAX_HEIGHT_DP = 8;

    /**
     * Injection point.
     * <p>
     * The page indicator of the comments button of the video metadata panel can have the dark theme colors,
     * as the text ({@link #fixMetapanelTextColors()}). The selected dot is the lightest one with the dark theme,
     * and the darkest one with the light theme, so the colors are inverted and keep their alpha.
     *
     * @return The color of a Litho background drawable.
     */
    public static int getBackgroundColor(Drawable drawable, int color) {
        try {
            if (color == 0 || !MetapanelTextColorFilter.isLightColor(color) || !fixMetapanelTextColors()) {
                return color;
            }

            // The dots of the indicator are circles, and the selected dot can be a pill.
            Rect bounds = drawable.getBounds();
            final int height = bounds.height();
            if (height == 0 || height > PAGE_INDICATOR_MAX_HEIGHT_DP
                    * Resources.getSystem().getDisplayMetrics().density
                    || bounds.width() > 3 * height) {
                return color;
            }

            return (color & 0xFF000000) | (~color & 0x00FFFFFF);
        } catch (Exception ex) {
            Logger.printException(() -> "getBackgroundColor failure", ex);
            return color;
        }
    }

    /**
     * Injection point.
     */
    public static boolean usePlayerBottomControlsExploderLayout(boolean original) {
        return !RESTORE_OLD_PLAYER_BUTTONS;
    }

    /**
     * Injection point.
     */
    public static boolean allowModernPlayerLayoutFlags(boolean original) {
        if (RESTORE_OLD_PLAYER_BUTTONS) {
            return false; // Flag causes app crash on startup if old player buttons is used.
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean useModernPlayerTopControls(boolean original) {
        if (original) {
            Logger.printDebug(() -> "useModernPlayerTopControls is set on");
        }

        return false;
    }
}
