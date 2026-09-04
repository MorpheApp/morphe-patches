/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerOverlayButtonsPatch {

    public static final int FULLSCREEN_HIDDEN_Y_OFFSET = 100000;

    private static final boolean HIDE_AUTOPLAY_BUTTON_ENABLED = Settings.HIDE_AUTOPLAY_BUTTON.get();
    private static final Boolean HIDE_FULLSCREEN_BUTTON_ENABLED = Settings.HIDE_FULLSCREEN_BUTTON.get();

    private static final int CONTROL_BUTTONS_BACKGROUND_OPACITY =
            SeekBarPreference.clampToRange(Settings.PLAYER_CONTROL_BUTTONS_BACKGROUND_OPACITY);

    /** At the app default the drawables are left untouched, so the stock look stays exact. */
    private static final boolean CONTROL_BUTTONS_BACKGROUND_OPACITY_CHANGED =
            CONTROL_BUTTONS_BACKGROUND_OPACITY
                    != Settings.PLAYER_CONTROL_BUTTONS_BACKGROUND_OPACITY.defaultValue;

    /**
     * Injection point.
     */
    public static boolean hideAutoplayButton() {
        return HIDE_AUTOPLAY_BUTTON_ENABLED;
    }

    /**
     * Injection point.
     */
    public static int hideCastButton(int original) {
        if (Settings.HIDE_CAST_BUTTON.get()) {
            return View.GONE;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static void hideCastButton(View parentView) {
        if (!Settings.HIDE_CAST_BUTTON.get()) {
            return;
        }

        hideView(parentView, "media_route_button");
    }

    /**
     * Injection point.
     */
    public static boolean hideCastButton(boolean original) {
        if (Settings.HIDE_CAST_BUTTON.get()) {
            return false;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static void hideCaptionsButton(ImageView imageView) {
        if (imageView == null) return;

        imageView.setVisibility(Settings.HIDE_CAPTIONS_BUTTON.get() ? ImageView.GONE : ImageView.VISIBLE);
    }

    /**
     * Injection point.
     */
    public static void hideCollapseButton(ImageView imageView) {
        if (!Settings.HIDE_COLLAPSE_BUTTON.get()) return;

        // Make the collapse button invisible
        imageView.setImageResource(android.R.color.transparent);
        imageView.setImageAlpha(0);
        imageView.setEnabled(false);

        // Adjust layout params if RelativeLayout
        var layoutParams = imageView.getLayoutParams();
        if (layoutParams instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(0, 0);
            imageView.setLayoutParams(lp);
        } else {
            Logger.printDebug(() -> "Unknown collapse button layout params: " + layoutParams);
        }
    }

    /**
     * Injection point.
     */
    public static void setTitleAnchorStartMargin(View titleAnchorView) {
        if (!Settings.HIDE_COLLAPSE_BUTTON.get()) return;

        var layoutParams = titleAnchorView.getLayoutParams();
        if (layoutParams instanceof RelativeLayout.LayoutParams relativeParams) {
            relativeParams.setMarginStart(0);
        } else {
            Logger.printDebug(() -> "Unknown title anchor layout params: " + layoutParams);
        }
    }

    private static final boolean HIDE_PLAYER_PREVIOUS_NEXT_BUTTONS_ENABLED
            = Settings.HIDE_PLAYER_PREVIOUS_NEXT_BUTTONS.get();

    /**
     * Injection point.
     */
    public static void hidePreviousNextButtons(View parentView) {
        if (!HIDE_PLAYER_PREVIOUS_NEXT_BUTTONS_ENABLED) {
            return;
        }

        hideView(parentView, "player_control_previous_button_touch_area");
        hideView(parentView, "player_control_next_button_touch_area");
    }


    /**
     * Injection point.
     */
    public static void hideSettingsButton(View parentView) {
        if (!Settings.HIDE_SETTINGS_BUTTON.get()) {
            return;
        }

        hideView(parentView, "player_overflow_button");
    }

    /**
     * Injection point.
     */
    public static ImageView hideFullscreenButton(ImageView imageView) {
        if (!HIDE_FULLSCREEN_BUTTON_ENABLED) {
            return imageView;
        }

        if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
            imageView.setVisibility(View.GONE);
            return null;
        }

        // Cannot remove the button because the bold overlay player buttons
        // rely on the draw updates to control fade in/out.
        // Move the button offscreen so it's not visible anymore.
        imageView.setY(imageView.getY() - FULLSCREEN_HIDDEN_Y_OFFSET);
        return imageView;
    }

    /**
     * Injection point.
     */
    public static View styleControlButtonsBackground(View rootView) {
        try {
            // Each button is an ImageView with a background set to another drawable.
            if (Settings.HIDE_PLAYER_CONTROL_BUTTONS_BACKGROUND.get()) {
                forEachImageViewRecursive(rootView, imageView -> imageView.setBackground(null));
            } else if (CONTROL_BUTTONS_BACKGROUND_OPACITY_CHANGED) {
                forEachImageViewRecursive(rootView, imageView -> {
                    Drawable background = imageView.getBackground();
                    if (background != null) {
                        imageView.setBackground(applyControlButtonsBackgroundOpacity(background));
                    }
                });
            }
        } catch (Exception ex) {
            Logger.printException(() -> "styleControlButtonsBackground failure", ex);
        }

        return rootView;
    }

    /**
     * Also used for the bottom overlay buttons, which copy their background from the
     * fullscreen button instead of declaring one in a layout.
     *
     * @return the drawable to use, unchanged if the opacity is left at the app default.
     */
    public static Drawable applyControlButtonsBackgroundOpacity(Drawable background) {
        if (CONTROL_BUTTONS_BACKGROUND_OPACITY_CHANGED
                && !Settings.HIDE_PLAYER_CONTROL_BUTTONS_BACKGROUND.get()) {
            // Mutate so the color does not leak into the same drawable used elsewhere.
            background = background.mutate();
            setSolidColorOpacityRecursive(background);
        }

        return background;
    }

    /**
     * The circle's transparency is baked into its solid color, so the color is replaced.
     * setAlpha() only scales what is already there and can never exceed the app default.
     */
    private static void setSolidColorOpacityRecursive(Drawable drawable) {
        if (drawable instanceof GradientDrawable gradient) {
            ColorStateList color = gradient.getColor();
            if (color != null) {
                final int rgb = color.getDefaultColor();
                // Replacing the alpha rather than scaling it keeps this safe to apply twice.
                gradient.setColor(Color.argb(
                        CONTROL_BUTTONS_BACKGROUND_OPACITY * 255 / 100,
                        Color.red(rgb),
                        Color.green(rgb),
                        Color.blue(rgb)));
            }
        } else if (drawable instanceof LayerDrawable layers) {
            for (int i = 0, count = layers.getNumberOfLayers(); i < count; i++) {
                setSolidColorOpacityRecursive(layers.getDrawable(i));
            }
        } else if (drawable instanceof DrawableWrapper wrapper) {
            // The bottom buttons get the circle wrapped in an InsetDrawable, which is not a
            // LayerDrawable and would otherwise be skipped.
            setSolidColorOpacityRecursive(wrapper.getDrawable());
        }
    }

    private static void hideView(View parentView, String name) {
        int resourceId = ResourceUtils.getIdentifierOrThrow(ResourceType.ID, name);

        // Must use a deferred call to main thread to hide the button.
        // Otherwise, the layout crashes if set to hidden now.
        Utils.runOnMainThread(() -> {
            View targetView = parentView.findViewById(resourceId);

            if (targetView == null) {
                Logger.printException(() -> "Could not find player button: R.id." + name);
                return;
            }

            Logger.printDebug(() -> "Hiding player button: R.id." + name);
            Utils.hideViewByRemovingFromParentUnderCondition(true, targetView);
        });
    }

    private static void forEachImageViewRecursive(View currentView, Consumer<ImageView> action) {
        if (currentView instanceof ImageView imageView) {
            action.accept(imageView);
        }

        if (currentView instanceof ViewGroup viewGroup) {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                forEachImageViewRecursive(viewGroup.getChildAt(i), action);
            }
        }
    }
}
