/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.preference.ColorPickerPreference.DISABLED_ALPHA;
import static app.morphe.extension.youtube.patches.LoopCountPatch.MAX_LOOP_COUNT;
import static app.morphe.extension.youtube.patches.LoopCountPatch.MIN_LOOP_COUNT;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.LoopCountPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Player settings menu item to choose how many times the app loops the current video.
 * In a playlist, the choice is remembered for the video in that playlist.
 */
@SuppressWarnings("unused")
public class LoopCountFlyoutButton {

    /** Shown for a video with no loop count, which the app loops without a limit. */
    private static final String NO_LIMIT_TEXT = "∞";

    /** The menu item that was shown last. */
    private static WeakReference<LoopCountStepper> shownStepperRef = new WeakReference<>(null);

    /**
     * Adds a menu item to the player menu that has the loop video item of the app,
     * which is the menu of the additional player settings.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex,
                                      String flyoutVideoId, boolean isTopFlyout) {
        try {
            // Read for every menu, so it does not apply to the next menu.
            final boolean isLoopVideoMenu = LoopCountPatch.readLoopVideoMenuItemCreated();
            Logger.printDebug(() -> "Loop video menu: " + isLoopVideoMenu + " top menu: " + isTopFlyout
                    + " menu video id: " + flyoutVideoId);
            if (!Settings.LOOP_COUNT.get() || !isLoopVideoMenu
                    || isTopFlyout || !flyoutVideoId.isEmpty()) {
                return nextButtonIndex;
            }

            final String videoId = VideoInformation.getVideoId();
            Context context = Utils.getActivity();
            if (videoId.isEmpty() || context == null) {
                return nextButtonIndex;
            }
            final String playlistId = VideoInformation.getPlaylistId();
            final int loopCount = LoopCountPatch.getLoopCount(playlistId, videoId);

            LoopCountStepper stepper = new LoopCountStepper(context, playlistId, videoId, loopCount);
            shownStepperRef = new WeakReference<>(stepper);

            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                            ? "yt_outline_experimental_hashtag_vd_theme_24"
                            : "yt_outline_hashtag_black_24"),
                    str("morphe_loop_count_flyout_name"),
                    v -> stepper.showInputDialog(),
                    nextButtonIndex,
                    stepper.view,
                    stepper.enabled
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
        }
        return nextButtonIndex;
    }

    /**
     * Called when the app turns its loop video on or off, which can happen while the menu is open.
     */
    public static void onLoopVideoChanged() {
        LoopCountStepper stepper = getShownStepper();
        if (stepper != null) {
            stepper.updateEnabled();
        }
    }

    /**
     * Called when the player status changes, which it does when the app plays another video.
     * The menu stays open while the app plays the next video, so it then shows the loop count of that video,
     * and a change of the loop count applies to that video.
     */
    public static void onPlayerStatusChanged() {
        try {
            LoopCountStepper stepper = getShownStepper();
            if (stepper == null) {
                return;
            }

            final String videoId = VideoInformation.getVideoId();
            if (videoId.isEmpty() || videoId.equals(stepper.videoId)) {
                return;
            }
            Logger.printDebug(() -> "Showing the loop count of the next video: " + videoId);
            stepper.setVideo(VideoInformation.getPlaylistId(), videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerStatusChanged failure", ex);
        }
    }

    /**
     * @return If a menu that is open shows the loop count.
     */
    public static boolean isShown() {
        return getShownStepper() != null;
    }

    /**
     * @return The menu item of a menu that is open, or null if no open menu shows the loop count.
     */
    @Nullable
    private static LoopCountStepper getShownStepper() {
        LoopCountStepper stepper = shownStepperRef.get();
        return stepper != null && stepper.view.isAttachedToWindow() ? stepper : null;
    }

    private static String formatLoopCount(int loopCount) {
        return loopCount > 0 ? String.valueOf(loopCount) : NO_LIMIT_TEXT;
    }

    /**
     * Arrows to change the loop count, and the loop count that can be tapped to type it.
     */
    private static final class LoopCountStepper {

        private final LinearLayout view;
        private final View decreaseButton;
        private final TextView valueView;
        private final View increaseButton;

        private String playlistId;
        private String videoId;
        /** If the loop count can be changed. */
        private boolean enabled;
        private int loopCount;

        LoopCountStepper(Context context, String playlistId, String videoId, int loopCount) {
            this.playlistId = playlistId;
            this.videoId = videoId;
            this.loopCount = loopCount;
            this.enabled = isEnabled();

            final int foregroundColor = ThemeUtils.getAppForegroundColor();

            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            // The arrows point to lower and higher numbers, which do not change direction with the language.
            view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

            decreaseButton = createArrowButton(context, foregroundColor, false);
            increaseButton = createArrowButton(context, foregroundColor, true);

            valueView = new TextView(context);
            valueView.setTextColor(foregroundColor);
            valueView.setTextSize(16);
            valueView.setGravity(Gravity.CENTER);
            valueView.setMinWidth(Dim.dp(40));
            valueView.setPadding(Dim.dp8, Dim.dp4, Dim.dp8, Dim.dp4);
            ShapeDrawable background = new ShapeDrawable(new RoundRectShape(Dim.roundedCorners(8), null, null));
            background.getPaint().setColor(ThemeUtils.getEditTextBackground());
            // The ripple is drawn over the box and stays inside it.
            valueView.setBackground(new RippleDrawable(getRippleColor(foregroundColor), background, null));
            valueView.setOnClickListener(v -> showInputDialog());

            decreaseButton.setOnClickListener(v -> setLoopCount(
                    this.loopCount <= MIN_LOOP_COUNT ? 0 : this.loopCount - 1));
            increaseButton.setOnClickListener(v -> setLoopCount(
                    this.loopCount <= 0 ? MIN_LOOP_COUNT : Math.min(this.loopCount + 1, MAX_LOOP_COUNT)));

            view.addView(decreaseButton);
            view.addView(valueView);
            view.addView(increaseButton);

            updateViews();
        }

        private static ImageView createArrowButton(Context context, int color, boolean increase) {
            ImageView button = new ImageView(context);
            button.setImageDrawable(ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                    ? "yt_outline_experimental_chevron_right_vd_theme_24"
                    : "yt_outline_chevron_right_black_24"));
            button.setImageTintList(ColorStateList.valueOf(color));
            button.setScaleType(ImageView.ScaleType.CENTER);
            if (!increase) {
                // The left arrow is the right arrow mirrored.
                button.setScaleX(-1);
            }

            final int size = Dim.dp(40);
            button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            button.setContentDescription(str(increase
                    ? "morphe_loop_count_increase"
                    : "morphe_loop_count_decrease"));
            // A borderless ripple is drawn by the first parent with a background, under the menu item.
            // This ripple is drawn by the arrow itself, in a circle.
            button.setBackground(new RippleDrawable(getRippleColor(color), null, new ShapeDrawable(new OvalShape())));
            return button;
        }

        private static ColorStateList getRippleColor(int foregroundColor) {
            // 20% of the foreground color.
            return ColorStateList.valueOf((foregroundColor & 0x00FFFFFF) | 0x33000000);
        }

        private void setLoopCount(int count) {
            loopCount = count;
            LoopCountPatch.setLoopCount(playlistId, videoId, count);
            updateEnabled();
            updateViews();
        }

        /**
         * The loop count limits the loop video of the app. It can be changed when the app loops the video,
         * and when the video already has a loop count, which turns on the loop video of the app.
         */
        boolean isEnabled() {
            return LoopCountPatch.isLoopVideoEnabled() || loopCount > 0;
        }

        /**
         * Shows the loop count of another video.
         */
        void setVideo(String playlistId, String videoId) {
            this.playlistId = playlistId;
            this.videoId = videoId;
            loopCount = LoopCountPatch.getLoopCount(playlistId, videoId);
            updateEnabled();
            updateViews();
        }

        void updateEnabled() {
            final boolean isEnabled = isEnabled();
            if (enabled != isEnabled) {
                enabled = isEnabled;
                FlyoutUtils.setFlyoutButtonWithEndViewEnabled(view, isEnabled);
                updateViews();
            }
        }

        private void updateViews() {
            valueView.setEnabled(enabled);
            valueView.setText(formatLoopCount(loopCount));
            valueView.setContentDescription(loopCount > 0
                    ? str("morphe_loop_count_times", String.valueOf(loopCount))
                    : str("morphe_loop_count_no_limit"));

            setArrowEnabled(decreaseButton, loopCount > 0);
            setArrowEnabled(increaseButton, loopCount < MAX_LOOP_COUNT);
        }

        private void setArrowEnabled(View arrow, boolean canChange) {
            arrow.setEnabled(enabled && canChange);
            // A menu item that is not enabled is already dimmed.
            arrow.setAlpha(!enabled || canChange ? 1.0f : DISABLED_ALPHA);
        }

        /**
         * Shows a dialog to type the loop count. The menu stays open, and shows the new loop count.
         */
        private void showInputDialog() {
            try {
                Context context = view.getContext();

                EditText editText = new EditText(context);
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setFilters(new InputFilter[]{
                        new InputFilter.LengthFilter(String.valueOf(MAX_LOOP_COUNT).length())
                });
                editText.setHint(NO_LIMIT_TEXT);
                if (loopCount > 0) {
                    editText.setText(String.valueOf(loopCount));
                    editText.setSelection(editText.getText().length());
                }

                Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                        context,
                        str("morphe_loop_count_flyout_name"),
                        str("morphe_loop_count_dialog_message",
                                String.valueOf(MIN_LOOP_COUNT), String.valueOf(MAX_LOOP_COUNT)),
                        editText,
                        null,
                        () -> {
                            try {
                                String text = editText.getText().toString().trim();
                                final int count = text.isEmpty() ? 0 : Integer.parseInt(text);
                                // Playing a video once does not loop it.
                                setLoopCount(count < MIN_LOOP_COUNT ? 0 : Math.min(count, MAX_LOOP_COUNT));
                            } catch (Exception ex) {
                                Logger.printException(() -> "Loop count input failure", ex);
                            }
                        },
                        () -> {},
                        null,
                        null,
                        false
                );

                dialogPair.first.show();
            } catch (Exception ex) {
                Logger.printException(() -> "showInputDialog failure", ex);
            }
        }
    }
}
