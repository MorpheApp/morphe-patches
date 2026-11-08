/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 * https://github.com/MorpheApp/morphe-patches/pull/2282
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.components.PlaybackSpeedMenuFilter;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

@SuppressWarnings("unused")
public class CustomPlaybackSpeedPatch {

    /**
     * How much +/- speed adjustment buttons change the current speed.
     */
    private static final double SPEED_ADJUSTMENT_CHANGE = 0.05;

    /**
     * How much +/- pitch adjustment buttons change the current audio pitch.
     */
    private static final double PITCH_ADJUSTMENT_CHANGE = 0.05;

    private static final int LINK_ICON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            "morphe_ic_link"
    );

    private static final int LINK_OFF_ICON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            "morphe_ic_link_off"
    );

    private static final int VIDEO_ICON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            "morphe_ic_slow_motion_video"
    );

    private static final int AUDIO_ICON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            "morphe_ic_music_note"
    );

    /**
     * One musical semitone ratio: 2^(1/12).
     */
    private static final double ONE_SEMITONE = Math.pow(2.0, 1.0 / 12.0);

    /**
     * Scale used to convert user speed to {@link android.widget.ProgressBar#setProgress(int)}.
     */
    private static final float PROGRESS_BAR_VALUE_SCALE = 100;

    /**
     * Disable tap and hold speed, true when TAP_AND_HOLD_SPEED is 0.
     */
    private static final boolean DISABLE_TAP_AND_HOLD_SPEED;

    /**
     * Tap and hold speed.
     */
    private static final float TAP_AND_HOLD_SPEED;

    /**
     * Tap and hold speed label.
     */
    private static final String tapAndHoldEduText = str("speedmaster_edu_text");

    /**
     * Custom playback speeds.
     */
    public static final float[] customPlaybackSpeeds;

    /**
     * Minimum and maximum custom playback speeds of {@link #customPlaybackSpeeds}.
     */
    private static final float customPlaybackSpeedsMin, customPlaybackSpeedsMax;

    /**
     * The last time the playback menu was forcefully called.
     */
    private static volatile long lastTimePlaybackMenuInvoked;

    static {
        final float holdSpeed = Settings.SPEED_TAP_AND_HOLD.get();
        DISABLE_TAP_AND_HOLD_SPEED = (holdSpeed == 0);

        if (DISABLE_TAP_AND_HOLD_SPEED) {
            // A value for handling exceptions, but this is not used.
            TAP_AND_HOLD_SPEED = Settings.SPEED_TAP_AND_HOLD.defaultValue;
        } else if (holdSpeed > 0 && holdSpeed <= VideoInformation.PLAYBACK_SPEED_MAXIMUM) {
            TAP_AND_HOLD_SPEED = holdSpeed;
        } else {
            showInvalidCustomSpeedToast();
            TAP_AND_HOLD_SPEED = Settings.SPEED_TAP_AND_HOLD.resetToDefault();
        }

        customPlaybackSpeeds = loadCustomSpeeds();
        customPlaybackSpeedsMin = customPlaybackSpeeds[0];
        customPlaybackSpeedsMax = customPlaybackSpeeds[customPlaybackSpeeds.length - 1];
    }

    /**
     * Injection point.
     * Called before {@link #getTapAndHoldSpeed()}
     */
    public static boolean disableTapAndHoldSpeed(boolean original) {
        return Logger.traceOverride("CustomPlaybackSpeedPatch.disableTapAndHoldSpeed", original,
                !DISABLE_TAP_AND_HOLD_SPEED && original);
    }

    /**
     * Injection point.
     */
    public static boolean restoreOldPlaybackSpeedMenu() {
        return Settings.RESTORE_OLD_SPEED_MENU.get();
    }

    /**
     * Injection point.
     */
    public static boolean useNewFlyoutMenu(boolean useNewFlyout) {
        // If using old speed Turn off A/B flyout that breaks old playback speed menu.
        return useNewFlyout && !Settings.RESTORE_OLD_SPEED_MENU.get();
    }

    /**
     * Injection point.
     * With audio offload the device hardware decodes the audio, and usually does not support changing the pitch.
     */
    public static boolean disableAudioOffload(boolean original) {
        return Logger.traceOverride("CustomPlaybackSpeedPatch.disableAudioOffload", original,
                original && !Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get());
    }

    /**
     * Injection point.
     */
    public static float getTapAndHoldSpeed() {
        return TAP_AND_HOLD_SPEED;
    }

    /**
     * Injection point.
     */
    public static CharSequence onSeekEduOverlayLoaded(ContextInterface context, CharSequence original) {
        try {
            if (!DISABLE_TAP_AND_HOLD_SPEED && TextUtils.equals(tapAndHoldEduText, original)) {
                String identifier = context.patch_getIdentifier();
                if (identifier != null && identifier.startsWith("seek_edu_overlay_v2.e")) {
                    // 2.00x → 2x, 1.50x → 1.5x.
                    return VideoInformation.formatSpeedStringX(TAP_AND_HOLD_SPEED)
                            .replace(".00x", "x")
                            .replace("0x", "x") + ' ';
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSeekEduOverlayLoaded failed", ex);
        }

        return original;
    }

    private static void showInvalidCustomSpeedToast() {
        Utils.showSnackbarLong(str("morphe_custom_playback_speeds_invalid", VideoInformation.PLAYBACK_SPEED_MAXIMUM));
    }

    private static float[] loadCustomSpeeds() {
        try {
            // Automatically replace commas with periods,
            // if the user added speeds in a localized format.
            String[] speedStrings = Settings.CUSTOM_PLAYBACK_SPEEDS.get()
                    .replace(',', '.').split("\\s+");
            Arrays.sort(speedStrings);
            if (speedStrings.length == 0) {
                throw new IllegalArgumentException();
            }

            float[] speeds = new float[speedStrings.length];

            int i = 0;
            for (String speedString : speedStrings) {
                final float speedFloat = Float.parseFloat(speedString);
                if (speedFloat <= 0 || arrayContains(speeds, speedFloat)) {
                    throw new IllegalArgumentException();
                }

                if (speedFloat > VideoInformation.PLAYBACK_SPEED_MAXIMUM) {
                    showInvalidCustomSpeedToast();
                    Settings.CUSTOM_PLAYBACK_SPEEDS.resetToDefault();
                    return loadCustomSpeeds();
                }

                speeds[i++] = speedFloat;
            }

            return speeds;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Parse error", ex);
            Utils.showSnackbarShort(str("morphe_custom_playback_speeds_parse_exception"));
            Settings.CUSTOM_PLAYBACK_SPEEDS.resetToDefault();
            return loadCustomSpeeds();
        }
    }

    private static boolean arrayContains(float[] array, float value) {
        for (float arrayValue : array) {
            if (arrayValue == value) return true;
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(RecyclerView recyclerView) {
        recyclerView.getViewTreeObserver().addOnDrawListener(() -> {
            try {
                if (PlaybackSpeedMenuFilter.isPlaybackRateSelectorMenuVisible) {
                    if (hideLithoMenuAndShowSpeedMenu(recyclerView, 5)) {
                        PlaybackSpeedMenuFilter.isPlaybackRateSelectorMenuVisible = false;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "isPlaybackRateSelectorMenuVisible failure", ex);
            }

            try {
                if (PlaybackSpeedMenuFilter.isOldPlaybackSpeedMenuVisible) {
                    if (hideLithoMenuAndShowSpeedMenu(recyclerView, 8)) {
                        PlaybackSpeedMenuFilter.isOldPlaybackSpeedMenuVisible = false;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "isOldPlaybackSpeedMenuVisible failure", ex);
            }
        });
    }

    private static boolean hideLithoMenuAndShowSpeedMenu(RecyclerView recyclerView, int expectedChildCount) {
        if (recyclerView.getChildCount() == 0) {
            return false;
        }

        if (!(recyclerView.getChildAt(0) instanceof ViewGroup playbackSpeedParentView)) {
            return false;
        }

        if (playbackSpeedParentView.getChildCount() != expectedChildCount) {
            return false;
        }

        if (!(Utils.getParentView(recyclerView, 3) instanceof ViewGroup parentView3rd)) {
            return false;
        }

        if (!(parentView3rd.getParent() instanceof ViewGroup parentView4th)) {
            return false;
        }

        // This method is sometimes used multiple times.
        // To prevent this, ignore method reuse within 1 second.
        final long now = System.currentTimeMillis();
        if (now - lastTimePlaybackMenuInvoked < 1000) {
            Logger.printDebug(() -> "Ignoring call to hideLithoMenuAndShowSpeedMenu");
            return true;
        }
        lastTimePlaybackMenuInvoked = now;

        // Dismiss View [R.id.touch_outside] is the 1st ChildView of the 4th ParentView.
        // This only shows in phone layout of YouTube 21.11 or lower.
        View touchInsidedView = parentView4th.getChildAt(0);
        touchInsidedView.callOnClick();

        // In tablet layout and phone layout of YouTube 21.12 or higher,
        // there no Dismiss View. Just hide two parent views.
        parentView3rd.setVisibility(View.GONE);
        parentView4th.setVisibility(View.GONE);

        // Close the litho speed menu and show the custom speeds.
        if (Settings.RESTORE_OLD_SPEED_MENU.get()) {
            showOldPlaybackSpeedMenu();
            Logger.printDebug(() -> "Old playback speed dialog shown");
        } else {
            showModernCustomPlaybackSpeedDialog(recyclerView.getContext());
            Logger.printDebug(() -> "Modern playback speed dialog shown");
        }

        return true;
    }

    public static void showOldPlaybackSpeedMenu() {
        // Rest of the implementation added by patch.
        Logger.printDebug(() -> "showOldPlaybackSpeedMenu");
    }

    /**
     * Displays a modern custom dialog for adjusting video playback speed.
     * <p>
     * This method creates a dialog with a slider, plus/minus buttons, and preset speed buttons
     * to allow the user to modify the video playback speed. The dialog is styled with rounded
     * corners and themed colors, positioned at the bottom of the screen. The playback speed
     * can be adjusted in 0.05 increments using the slider or buttons, or set directly to preset
     * values. The dialog updates the displayed speed in real-time and applies changes to the
     * video playback. The dialog is dismissed if the player enters Picture-in-Picture (PiP) mode.
     */
    @SuppressLint("SetTextI18n")
    public static void showModernCustomPlaybackSpeedDialog(Context context) {
        try {
            // # Create main layout.
            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, LegacyPlayerControlButton.getDialogBackgroundColor());

            // Capped so the dialog remains fully usable in landscape, where height is limited.
            ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context);

            LinearLayout contentLayout = new LinearLayout(context);
            contentLayout.setOrientation(LinearLayout.VERTICAL);

            // ## Speed UI Elements
            // Passes value to VideoInformation when user picks a new playback speed.
            Consumer<Float> userSelectedSpeed = VideoInformation::setPlaybackSpeed;

            // Display current playback speed with a leading video icon.
            // The icon only tells speed and pitch rows apart, so it is noise without the pitch row.
            FrameLayout speedLabelRow = createLabelRow(context,
                    Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get() ? VIDEO_ICON : 0);
            float currentSpeed = VideoInformation.getPlaybackSpeed();
            TextView currentSpeedText = createValueText(context, VideoInformation.formatSpeedStringX(currentSpeed));
            speedLabelRow.addView(currentSpeedText);
            contentLayout.addView(speedLabelRow);

            SeekBar speedSlider = createSlider(context, currentSpeed,
                    CustomPlaybackSpeedPatch::roundSpeedToNearestIncrement, userSelectedSpeed);
            contentLayout.addView(createSliderRow(context, speedSlider, SPEED_ADJUSTMENT_CHANGE,
                    CustomPlaybackSpeedPatch::roundSpeedToNearestIncrement,
                    VideoInformation::getPlaybackSpeed, userSelectedSpeed));

            // The first observer to keep label text and slider reactive to speed changes.
            // observer is removed on dialog dismissal in order.
            Consumer<Float> onSpeedChanged = speed -> {
                currentSpeedText.setText(VideoInformation.formatSpeedStringX(speed));
                speedSlider.setProgress(valueToProgress(speed));
            };

            VideoInformation.onPlaybackSpeedChange.addObserver(onSpeedChanged);

            // Add buttons for each preset playback speed.
            GridLayout speedPresetGrid = createPresetGrid(context,
                    (int) Math.ceil(customPlaybackSpeeds.length / 5.0));
            for (float speed : customPlaybackSpeeds) {
                FrameLayout buttonContainer = createPresetButton(context,
                        VideoInformation.formatSpeedStringX(speed, 1),
                        v -> userSelectedSpeed.accept(speed));

                // Add "Normal" label for 1.0x speed.
                if (speed == 1.0f) {
                    TextView normalLabel = new TextView(context);
                    // Use same 'Normal' string as stock YouTube.
                    normalLabel.setText(str("normal_playback_rate_label"));
                    normalLabel.setTextColor(ThemeUtils.getAppForegroundColor());
                    normalLabel.setTextSize(10);
                    normalLabel.setGravity(Gravity.CENTER);
                    normalLabel.setSingleLine(true);
                    normalLabel.setEllipsize(TextUtils.TruncateAt.END);
                    // Position label below button.
                    normalLabel.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

                    buttonContainer.addView(normalLabel);
                }

                speedPresetGrid.addView(buttonContainer);
            }
            contentLayout.addView(speedPresetGrid);

            // ## Audio pitch UI: link toggle and pitch controls, hidden entirely when disabled.
            final Consumer<Float> onPitchChanged;
            if (Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get()) {
                // ## Link Button
                LinearLayout linkLayout = new LinearLayout(context);
                linkLayout.setOrientation(LinearLayout.HORIZONTAL);
                linkLayout.setGravity(Gravity.CENTER);

                boolean isTimeStretching = Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
                Button linkButton = createRoundButton(context,
                        context.getDrawable(isTimeStretching ? LINK_OFF_ICON : LINK_ICON));
                linkButton.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
                linkButton.setOnClickListener(v -> {
                    boolean newValue = !Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
                    Settings.PLAYBACK_AUDIO_TIME_STRETCHING.save(newValue);
                    linkButton.setForeground(context.getDrawable(newValue ? LINK_OFF_ICON : LINK_ICON));

                    // When switching from unlinked to linked (true to false), sync audio pitch to the video speed.
                    if (!newValue) {
                        VideoInformation.setAudioPitch(VideoInformation.getPlaybackSpeed());
                    }
                });
                linkLayout.addView(linkButton);
                contentLayout.addView(linkLayout);

                // ## Pitch UI elements
                // Passes value to VideoInformation when user picks a new audio pitch.
                Consumer<Float> userSelectedPitch = VideoInformation::setAudioPitch;

                // Display current playback audio pitch with a leading audio icon.
                FrameLayout pitchLabelRow = createLabelRow(context, AUDIO_ICON);
                float currentPitch = VideoInformation.getPlaybackAudioPitch();
                TextView currentPitchText = createValueText(context, VideoInformation.formatAudioPitchStringX(currentPitch));
                pitchLabelRow.addView(currentPitchText);
                contentLayout.addView(pitchLabelRow);

                SeekBar pitchSlider = createSlider(context, currentPitch,
                        CustomPlaybackSpeedPatch::roundPitchToNearestIncrement, userSelectedPitch);
                contentLayout.addView(createSliderRow(context, pitchSlider, PITCH_ADJUSTMENT_CHANGE,
                        CustomPlaybackSpeedPatch::roundPitchToNearestIncrement,
                        VideoInformation::getPlaybackAudioPitch, userSelectedPitch));

                // Observer to keep the pitch label and slider reactive to pitch changes.
                // Removed on dialog dismissal in order.
                onPitchChanged = pitch -> {
                    currentPitchText.setText(VideoInformation.formatAudioPitchStringX(pitch));
                    pitchSlider.setProgress(valueToProgress(pitch));
                };

                VideoInformation.onPlaybackAudioPitchChange.addObserver(onPitchChanged);

                GridLayout pitchPresetGrid = createPresetGrid(context, 1);
                // Buttons: /2 (half) | −1st (down one semitone) | 1x (reset)
                // | +1st (up one semitone) | ×2 (double)
                String[] pitchButtonLabels = {"/2", "−1st", "1x", "+1st", "×2"};
                for (String pitchLabel : pitchButtonLabels) {
                    pitchPresetGrid.addView(createPresetButton(context, pitchLabel, v -> {
                        final float pitch = VideoInformation.getPlaybackAudioPitch();
                        final float newValue = switch (pitchLabel) {
                            case "/2" -> pitch * 0.5f;
                            case "−1st" -> (float) (pitch / ONE_SEMITONE);
                            case "1x" -> 1.0f;
                            case "+1st" -> (float) (pitch * ONE_SEMITONE);
                            case "×2" -> pitch * 2.0f;
                            default -> pitch;
                        };
                        // Not rounded, a semitone step is deliberately off the 0.05 grid.
                        userSelectedPitch.accept(clampPitch(newValue));
                    }));
                }
                contentLayout.addView(pitchPresetGrid);
            } else {
                onPitchChanged = null;
            }

            // Add content layout to main scroll view.
            scrollView.addView(contentLayout);
            mainLayout.addView(scrollView);

            // Create dialog.
            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, LegacyPlayerControlButton.fadeInDuration);

            // Unsubscribe from the speed and pitch events when the dialog is dismissed.
            dialog.setOnDismissListener(d -> {
                VideoInformation.onPlaybackSpeedChange.removeObserver(onSpeedChanged);
                if (onPitchChanged != null) {
                    VideoInformation.onPlaybackAudioPitchChange.removeObserver(onPitchChanged);
                }
            });

            PipDismissHelper.dismissOnPip(dialog);
            dialog.show();

        } catch (Exception ex) {
            Logger.printException(() -> "showModernCustomPlaybackSpeedDialog failure", ex);
        }
    }

    /**
     * Creates a styled button with a plus or minus symbol.
     *
     * @param context The Android context used to create the button.
     * @param isPlus  True to display a plus symbol, false to display a minus symbol.
     * @return A configured {@link Button} with the specified styling and layout parameters.
     */
    public static Button createStyledButton(Context context, boolean isPlus) {
        return createRoundButton(context, new OutlineSymbolDrawable(isPlus)); // Plus or minus symbol.
    }

    /**
     * Creates a square rounded button that shows only the given foreground drawable.
     */
    private static Button createRoundButton(Context context, Drawable foreground) {
        Button button = new Button(context, null, 0); // Disable default theme style.
        button.setBackground(createButtonBackground());
        button.setForeground(foreground);
        ViewAnimations.applyPressEffect(button);
        final int size = Utils.appIsUsingBoldIcons() ? Dim.dp40 : Dim.dp36;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(Dim.dp8, 0, Dim.dp8, 0); // Set margins.
        button.setLayoutParams(params);
        return button;
    }

    private static ShapeDrawable createButtonBackground() {
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(20), null, null));
        background.getPaint().setColor(getAdjustedBackgroundColor(false));
        return background;
    }

    /**
     * Creates the large bold text that shows the current speed or pitch value.
     */
    private static TextView createValueText(Context context, CharSequence text) {
        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextColor(ThemeUtils.getAppForegroundColor());
        textView.setTextSize(16);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(Gravity.CENTER);
        textView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return textView;
    }

    /**
     * Creates a themed slider over the custom speed range that reports user drags to {@code onUserValue}.
     */
    private static SeekBar createSlider(Context context, float value, UnaryOperator<Float> round,
                                       Consumer<Float> onUserValue) {
        SeekBar slider = new SeekBar(context);
        slider.setFocusable(true);
        slider.setFocusableInTouchMode(true);
        slider.setMax(valueToProgress(customPlaybackSpeedsMax));
        slider.setProgress(valueToProgress(value));
        slider.getProgressDrawable().setColorFilter(
                new PorterDuffColorFilter(ThemeUtils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN)); // Theme progress bar.
        slider.getThumb().setColorFilter(
                new PorterDuffColorFilter(ThemeUtils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN)); // Theme slider thumb.
        slider.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // Convert from progress value to speed or pitch.
                    onUserValue.accept(round.apply(
                            customPlaybackSpeedsMin + (progress / PROGRESS_BAR_VALUE_SCALE)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        return slider;
    }

    /**
     * Creates the horizontal "-, slider, +" row. The buttons step the value read from {@code currentValue}.
     */
    private static LinearLayout createSliderRow(Context context, SeekBar slider, double step,
                                                UnaryOperator<Float> round, Supplier<Float> currentValue,
                                                Consumer<Float> onUserValue) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button minusButton = createStyledButton(context, false);
        Button plusButton = createStyledButton(context, true);
        minusButton.setOnClickListener(v -> onUserValue.accept(
                round.apply((float) (currentValue.get() - step))));
        plusButton.setOnClickListener(v -> onUserValue.accept(
                round.apply((float) (currentValue.get() + step))));

        row.addView(minusButton);
        row.addView(slider);
        row.addView(plusButton);
        return row;
    }

    /**
     * Creates a five column grid for preset buttons.
     */
    private static GridLayout createPresetGrid(Context context, int rowCount) {
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(5);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setRowCount(rowCount);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.setMargins(Dim.dp4, Dim.dp12, Dim.dp4, Dim.dp12);
        grid.setLayoutParams(gridParams);
        return grid;
    }

    /**
     * Creates a preset button inside a fixed height grid cell.
     * The cell is taller than the button so a caption can be added below it.
     *
     * @return The grid cell container holding the button.
     */
    private static FrameLayout createPresetButton(Context context, CharSequence label,
                                                  View.OnClickListener onClick) {
        FrameLayout container = new FrameLayout(context);
        GridLayout.LayoutParams containerParams = new GridLayout.LayoutParams();
        containerParams.width = 0; // Equal width for columns.
        containerParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        containerParams.setMargins(Dim.dp4, 0, Dim.dp4, 0);
        containerParams.height = Dim.dp(60); // Fixed height for button and label.
        container.setLayoutParams(containerParams);

        Button button = new Button(context, null, 0);
        button.setText(label);
        button.setTextColor(ThemeUtils.getAppForegroundColor());
        button.setTextSize(12);
        button.setTypeface(Utils.appIsUsingBoldIcons()
                ? Typeface.DEFAULT_BOLD
                : Typeface.DEFAULT);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(createButtonBackground());
        button.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
        ViewAnimations.applyPressEffect(button);
        // Center button vertically and stretch horizontally in container.
        button.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Dim.dp32, Gravity.CENTER));
        button.setOnClickListener(onClick);

        container.addView(button);
        return container;
    }

    /**
     * Creates a row containing a leading icon and a centered value label.
     * The icon is offset from the left edge without affecting the centered position of the value.
     *
     * @param context The Android context used to create the row.
     * @param iconId  The drawable resource id of the leading icon, or 0 for no icon.
     * @return A configured {@link FrameLayout} containing the icon and centered value label.
     */
    private static FrameLayout createLabelRow(Context context, int iconId) {
        FrameLayout row = new FrameLayout(context);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp20, 0, 0);
        row.setLayoutParams(rowParams);

        if (iconId == 0) {
            return row;
        }

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconId);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                Dim.dp20, Dim.dp20, Gravity.START | Gravity.CENTER_VERTICAL);
        // Offset the icon so its center sits halfway between the center and the left edge of the dialog.
        iconParams.leftMargin = Dim.pctPortraitWidth(25) - Dim.dp10;
        icon.setLayoutParams(iconParams);

        row.addView(icon);
        return row;
    }

    /**
     * @return speed or audio pitch converted to a value for {@link SeekBar#setProgress(int)}.
     */
    private static int valueToProgress(float value) {
        return (int) ((value - customPlaybackSpeedsMin) * PROGRESS_BAR_VALUE_SCALE);
    }

    /**
     * Rounds the given playback speed to the nearest 0.05 increment,
     * unless the speed exactly matches a preset custom speed.
     *
     * @param speed The playback speed to round.
     * @return The rounded speed, constrained to the specified bounds.
     */
    private static float roundSpeedToNearestIncrement(float speed) {
        // Allow speed as-is if it exactly matches a speed preset such as 1.03x.
        if (arrayContains(customPlaybackSpeeds, speed)) {
            return speed;
        }

        // Round to nearest 0.05 speed.  Must use double precision otherwise rounding error can occur.
        final double roundedSpeed = Math.round(speed / SPEED_ADJUSTMENT_CHANGE) * SPEED_ADJUSTMENT_CHANGE;
        return Utils.clamp((float) roundedSpeed, (float) SPEED_ADJUSTMENT_CHANGE, VideoInformation.PLAYBACK_SPEED_MAXIMUM);
    }

    /**
     * Rounds the given audio pitch to the nearest 0.05 increment, within the valid pitch range.
     */
    private static float roundPitchToNearestIncrement(float pitch) {
        // Double precision for the same reason as the speed rounding.
        final double roundedPitch = Math.round(pitch / PITCH_ADJUSTMENT_CHANGE) * PITCH_ADJUSTMENT_CHANGE;
        return clampPitch((float) roundedPitch);
    }

    /**
     * Keeps the audio pitch within the range the player accepts.
     */
    private static float clampPitch(float pitch) {
        return Utils.clamp(pitch, (float) PITCH_ADJUSTMENT_CHANGE, VideoInformation.PLAYBACK_AUDIO_PITCH_MAXIMUM);
    }

    /**
     * Adjusts the background color based on the current theme.
     *
     * @param isHandleBar If true, applies a stronger darkening factor (0.9) for the handle bar in light theme;
     *                    if false, applies a standard darkening factor (0.95) for other elements in light theme.
     * @return A modified background color, lightened by 20% for dark themes or darkened by 5% (or 10% for handle bar)
     *         for light themes to ensure visual contrast.
     */
    public static int getAdjustedBackgroundColor(boolean isHandleBar) {
        // 1.25f for handleBar, 1.115f for others in dark theme.
        final float darkThemeFactor = isHandleBar ? 1.25f : 1.115f;
        // 0.9f for handleBar, 0.95f for others in light theme.
        final float lightThemeFactor = isHandleBar ? 0.9f : 0.95f;
        return Utils.adjustColorBrightness(LegacyPlayerControlButton.getDialogBackgroundColor(),
                lightThemeFactor, darkThemeFactor);
    }
}

/**
 * Custom Drawable for rendering outlined plus and minus symbols on buttons.
 */
class OutlineSymbolDrawable extends Drawable {
    private final boolean isPlus; // Determines if the symbol is a plus or minus.
    private final Paint paint;

    OutlineSymbolDrawable(boolean isPlus) {
        this.isPlus = isPlus;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG); // Enable antialiasing for smooth rendering.
        paint.setColor(ThemeUtils.getAppForegroundColor());
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Utils.appIsUsingBoldIcons() ? Dim.dp2 : Dim.dp1);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        final int width = bounds.width();
        final int height = bounds.height();
        final float centerX = width / 2f; // Center X coordinate.
        final float centerY = height / 2f; // Center Y coordinate.
        final float size = Math.min(width, height) * 0.25f; // Symbol size is 25% of button dimensions.

        // Draw horizontal line for both plus and minus symbols.
        canvas.drawLine(centerX - size, centerY, centerX + size, centerY, paint);
        if (isPlus) {
            // Draw vertical line for plus symbol.
            canvas.drawLine(centerX, centerY - size, centerX, centerY + size, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
