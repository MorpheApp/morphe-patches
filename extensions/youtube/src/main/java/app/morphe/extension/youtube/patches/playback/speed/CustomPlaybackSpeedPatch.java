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

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.components.PlaybackSpeedMenuFilter;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.shared.PlayerType;
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
        return !DISABLE_TAP_AND_HOLD_SPEED && original;
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
     */
    public static float getTapAndHoldSpeed() {
        return TAP_AND_HOLD_SPEED;
    }

    /**
     * Injection point.
     */
    public static CharSequence onSeekEduOverlayLoaded(Object context, CharSequence original) {
        if (!DISABLE_TAP_AND_HOLD_SPEED && TextUtils.equals(tapAndHoldEduText, original)
                && context instanceof ContextInterface contextInterface) {
            try {
                String identifier = contextInterface.patch_getIdentifier();
                if (identifier != null && identifier.startsWith("seek_edu_overlay_v2.e")) {
                    // 2.00x → 2x, 1.50x → 1.5x.
                    return VideoInformation.formatSpeedStringX(TAP_AND_HOLD_SPEED)
                            .replace(".00x", "x")
                            .replace("0x", "x") + ' ';
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onSeekEduOverlayLoaded failed", ex);
            }
        }

        return original;
    }

    private static void showInvalidCustomSpeedToast() {
        Utils.showToastLong(str("morphe_custom_playback_speeds_invalid", VideoInformation.PLAYBACK_SPEED_MAXIMUM));
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
            Utils.showToastShort(str("morphe_custom_playback_speeds_parse_exception"));
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

            // Wrap the dialog content in a ScrollView capped to most of the screen height,
            // so the dialog remains fully usable in landscape where the available height is limited.
            ScrollView scrollView = new ScrollView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(Dim.pctHeight(75), MeasureSpec.AT_MOST);
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            };
            scrollView.setVerticalScrollBarEnabled(false);

            LinearLayout contentLayout = new LinearLayout(context);
            contentLayout.setOrientation(LinearLayout.VERTICAL);

            // ## Speed UI Elements
            // Passes value to VideoInformation when user picks a new playback speed.
            Consumer<Float> userSelectedSpeed = VideoInformation::setPlaybackSpeed;

            // Display current playback speed with a leading video icon.
            FrameLayout speedLabelRow = createLabelRow(context, VIDEO_ICON);
            TextView currentSpeedText = new TextView(context);
            float currentSpeed = VideoInformation.getPlaybackSpeed();
            currentSpeedText.setText(VideoInformation.formatSpeedStringX(currentSpeed));
            currentSpeedText.setTextColor(Utils.getAppForegroundColor());
            currentSpeedText.setTextSize(16);
            currentSpeedText.setTypeface(Typeface.DEFAULT_BOLD);
            currentSpeedText.setGravity(Gravity.CENTER);
            currentSpeedText.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

            // Create horizontal layout for slider and +/- buttons.
            LinearLayout speedSliderLayout = new LinearLayout(context);
            speedSliderLayout.setOrientation(LinearLayout.HORIZONTAL);
            speedSliderLayout.setGravity(Gravity.CENTER_VERTICAL);

            // Create +/- buttons.
            Button speedMinusButton = createStyledButton(context, false);
            Button speedPlusButton = createStyledButton(context, true);

            speedMinusButton.setOnClickListener(v -> userSelectedSpeed.accept(roundSpeedToNearestIncrement(
                    (float) (VideoInformation.getPlaybackSpeed() - SPEED_ADJUSTMENT_CHANGE))));
            speedPlusButton.setOnClickListener(v -> userSelectedSpeed.accept(roundSpeedToNearestIncrement(
                    (float) (VideoInformation.getPlaybackSpeed() + SPEED_ADJUSTMENT_CHANGE))));

            // Create slider for speed adjustment.
            SeekBar speedSlider = new SeekBar(context);
            speedSlider.setFocusable(true);
            speedSlider.setFocusableInTouchMode(true);
            speedSlider.setMax(speedToProgressValue(customPlaybackSpeedsMax));
            speedSlider.setProgress(speedToProgressValue(currentSpeed));
            speedSlider.getProgressDrawable().setColorFilter(
                    new PorterDuffColorFilter(Utils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN)); // Theme progress bar.
            speedSlider.getThumb().setColorFilter(
                    new PorterDuffColorFilter(Utils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN)); // Theme slider thumb.
            LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            speedSlider.setLayoutParams(sliderParams);

            // Set listener for slider to update playback speed.
            speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        // Convert from progress value to video playback speed.
                        userSelectedSpeed.accept(roundSpeedToNearestIncrement(customPlaybackSpeedsMin + (progress / PROGRESS_BAR_VALUE_SCALE)));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            // The first observer to keep label text and slider reactive to speed changes.
            // observer is removed on dialog dismissal in order.
            Function1<Float, Unit> onSpeedChanged = speed -> {
                currentSpeedText.setText(VideoInformation.formatSpeedStringX(speed));
                speedSlider.setProgress(speedToProgressValue(speed));
                return Unit.INSTANCE;
            };

            VideoInformation.onPlaybackSpeedChange.addObserver(onSpeedChanged);

            // Create GridLayout for preset speed buttons.
            GridLayout gridLayout = new GridLayout(context);
            gridLayout.setColumnCount(5); // 5 columns for speed buttons.
            gridLayout.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            gridLayout.setRowCount((int) Math.ceil(customPlaybackSpeeds.length / 5.0));
            LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gridParams.setMargins(Dim.dp4, Dim.dp12, Dim.dp4, Dim.dp12); // Speed buttons container.
            gridLayout.setLayoutParams(gridParams);

            float[] roundedCorners20 = Dim.roundedCorners(20);
            final int adjustedBackgroundColor = getAdjustedBackgroundColor(false);
            // Add buttons for each preset playback speed.
            for (float speed : customPlaybackSpeeds) {
                // Container for button and optional label.
                FrameLayout buttonContainer = new FrameLayout(context);

                // Set layout parameters for each grid cell.
                GridLayout.LayoutParams containerParams = new GridLayout.LayoutParams();
                containerParams.width = 0; // Equal width for columns.
                containerParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                containerParams.setMargins(Dim.dp4, 0, Dim.dp4, 0); // Button margins.
                containerParams.height = Dim.dp(60); // Fixed height for button and label.
                buttonContainer.setLayoutParams(containerParams);

                // Create speed button.
                Button speedButton = new Button(context, null, 0);
                speedButton.setText(VideoInformation.formatSpeedStringX(speed, 1));
                speedButton.setTextColor(Utils.getAppForegroundColor());
                speedButton.setTextSize(12);
                speedButton.setTypeface(Utils.appIsUsingBoldIcons()
                        ? Typeface.DEFAULT_BOLD
                        : Typeface.DEFAULT);
                speedButton.setAllCaps(false);
                speedButton.setGravity(Gravity.CENTER);

                ShapeDrawable buttonBackground = new ShapeDrawable(new RoundRectShape(
                        roundedCorners20, null, null));
                buttonBackground.getPaint().setColor(adjustedBackgroundColor);
                speedButton.setBackground(buttonBackground);
                speedButton.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
                ViewAnimations.applyPressEffect(speedButton);

                // Center button vertically and stretch horizontally in container.
                FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, Dim.dp32, Gravity.CENTER);
                speedButton.setLayoutParams(buttonParams);

                // Add speed buttons view to buttons container layout.
                buttonContainer.addView(speedButton);

                // Add "Normal" label for 1.0x speed.
                if (speed == 1.0f) {
                    TextView normalLabel = new TextView(context);
                    // Use same 'Normal' string as stock YouTube.
                    normalLabel.setText(str("normal_playback_rate_label"));
                    normalLabel.setTextColor(Utils.getAppForegroundColor());
                    normalLabel.setTextSize(10);
                    normalLabel.setGravity(Gravity.CENTER);
                    normalLabel.setSingleLine(true);
                    normalLabel.setEllipsize(TextUtils.TruncateAt.END);

                    FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                    labelParams.bottomMargin = 0; // Position label below button.
                    normalLabel.setLayoutParams(labelParams);

                    buttonContainer.addView(normalLabel);
                }

                speedButton.setOnClickListener(v -> userSelectedSpeed.accept(speed));

                gridLayout.addView(buttonContainer);
            }

            // Speed UI Layout tree

            // Add Text to the label row with icon and text.
            speedLabelRow.addView(currentSpeedText);
            contentLayout.addView(speedLabelRow);
            // Add -/+ and slider views to slider layout.
            speedSliderLayout.addView(speedMinusButton);
            speedSliderLayout.addView(speedSlider);
            speedSliderLayout.addView(speedPlusButton);
            // Add slider layout to content layout.
            contentLayout.addView(speedSliderLayout);
            // Add in-rows speed buttons layout to content layout.
            contentLayout.addView(gridLayout);

            // ## Audio pitch UI: link toggle and pitch controls, hidden entirely when disabled.
            final Function1<Float, Unit> onPitchChanged;
            if (Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get()) {
                // ## Link Button
                LinearLayout linkLayout = new LinearLayout(context);
                linkLayout.setOrientation(LinearLayout.HORIZONTAL);
                linkLayout.setGravity(Gravity.CENTER);

                Button linkButton = new Button(context, null, 0);
                boolean isTimeStretching = Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
                linkButton.setForeground(context.getDrawable(isTimeStretching ? LINK_OFF_ICON : LINK_ICON));
                ShapeDrawable linkBackground = new ShapeDrawable(new RoundRectShape(
                        roundedCorners20, null, null));
                linkBackground.getPaint().setColor(adjustedBackgroundColor);
                linkButton.setBackground(linkBackground);
                linkButton.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
                final int linkSize = Utils.appIsUsingBoldIcons() ? Dim.dp40 : Dim.dp36;
                LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(linkSize, linkSize);
                linkParams.setMargins(Dim.dp8, 0, Dim.dp8, 0);
                linkButton.setLayoutParams(linkParams);
                linkButton.setOnClickListener(v -> {
                    boolean newValue = !Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
                    Settings.PLAYBACK_AUDIO_TIME_STRETCHING.save(newValue);
                    linkButton.setForeground(context.getDrawable(newValue ? LINK_OFF_ICON : LINK_ICON));

                    // When switching from unlinked to linked (true to false), sync audio pitch to the video speed.
                    if (!newValue) {
                        VideoInformation.setAudioPitch(VideoInformation.getPlaybackSpeed());
                    }
                });

                // ## Pitch UI elements
                // Passes value to VideoInformation when user picks a new audio pitch.
                Consumer<Float> userSelectedPitch = VideoInformation::setAudioPitch;

                // Display current playback audio pitch with a leading audio icon.
                FrameLayout pitchLabelRow = createLabelRow(context, AUDIO_ICON);
                TextView currentPitchText = new TextView(context);
                float currentPitch = VideoInformation.getPlaybackAudioPitch();
                currentPitchText.setText(VideoInformation.formatAudioPitchStringX(currentPitch));
                currentPitchText.setTextColor(Utils.getAppForegroundColor());
                currentPitchText.setTextSize(16);
                currentPitchText.setTypeface(Typeface.DEFAULT_BOLD);
                currentPitchText.setGravity(Gravity.CENTER);
                currentPitchText.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

                // Create horizontal layout for slider and +/- buttons.
                LinearLayout pitchSliderLayout = new LinearLayout(context);
                pitchSliderLayout.setOrientation(LinearLayout.HORIZONTAL);
                pitchSliderLayout.setGravity(Gravity.CENTER_VERTICAL);

                // Create +/- buttons.
                Button pitchMinusButton = createStyledButton(context, false);
                Button pitchPlusButton = createStyledButton(context, true);

                pitchMinusButton.setOnClickListener(v -> userSelectedPitch.accept(roundSpeedToNearestIncrement(
                        (float) (VideoInformation.getPlaybackAudioPitch() - PITCH_ADJUSTMENT_CHANGE))));
                pitchPlusButton.setOnClickListener(v -> userSelectedPitch.accept(roundSpeedToNearestIncrement(
                        (float) (VideoInformation.getPlaybackAudioPitch() + PITCH_ADJUSTMENT_CHANGE))));

                SeekBar pitchSlider = new SeekBar(context);
                pitchSlider.setFocusable(true);
                pitchSlider.setFocusableInTouchMode(true);
                pitchSlider.setMax(pitchToProgressValue(customPlaybackSpeedsMax));
                pitchSlider.setProgress(pitchToProgressValue(currentPitch));
                pitchSlider.getProgressDrawable().setColorFilter(
                        new PorterDuffColorFilter(Utils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN));
                pitchSlider.getThumb().setColorFilter(
                        new PorterDuffColorFilter(Utils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN));
                LinearLayout.LayoutParams pitchSliderParams = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                pitchSlider.setLayoutParams(pitchSliderParams);

                pitchSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            userSelectedPitch.accept(roundSpeedToNearestIncrement(
                                    customPlaybackSpeedsMin + (progress / PROGRESS_BAR_VALUE_SCALE)));
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });

                // Observer to keep the pitch label and slider reactive to pitch changes.
                // Removed on dialog dismissal in order.
                onPitchChanged = pitch -> {
                    currentPitchText.setText(VideoInformation.formatAudioPitchStringX(pitch));
                    pitchSlider.setProgress(pitchToProgressValue(pitch));
                    return Unit.INSTANCE;
                };

                VideoInformation.onPlaybackAudioPitchChange.addObserver(onPitchChanged);
            
                // Create GridLayout for pitch control buttons.
                //noinspection ExtractMethodRecommender
                GridLayout pitchPresetGrid = new GridLayout(context);
                pitchPresetGrid.setColumnCount(5);
                pitchPresetGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
                pitchPresetGrid.setRowCount(1);
                LinearLayout.LayoutParams pitchGridParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                pitchGridParams.setMargins(Dim.dp4, Dim.dp12, Dim.dp4, Dim.dp12);
                pitchPresetGrid.setLayoutParams(pitchGridParams);

                // Buttons: /2 (half) | −1st (down one semitone) | 1x (reset)
                // | +1st (up one semitone) | ×2 (double)
                String[] pitchButtonLabels = {"/2", "−1st", "1x", "+1st", "×2"};
                // Add buttons for the five options.
                for (String pitchLabel : pitchButtonLabels) {
                    FrameLayout pitchButtonContainer = new FrameLayout(context);
                    GridLayout.LayoutParams pitchContainerParams = new GridLayout.LayoutParams();
                    pitchContainerParams.width = 0;
                    pitchContainerParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                    pitchContainerParams.setMargins(Dim.dp4, 0, Dim.dp4, 0);
                    pitchContainerParams.height = Dim.dp(60);
                    pitchButtonContainer.setLayoutParams(pitchContainerParams);

                    Button pitchPresetButton = new Button(context, null, 0);
                    pitchPresetButton.setText(pitchLabel);
                    pitchPresetButton.setTextColor(Utils.getAppForegroundColor());
                    pitchPresetButton.setTextSize(12);
                    pitchPresetButton.setTypeface(Utils.appIsUsingBoldIcons()
                            ? Typeface.DEFAULT_BOLD
                            : Typeface.DEFAULT);
                    pitchPresetButton.setAllCaps(false);
                    pitchPresetButton.setGravity(Gravity.CENTER);

                    ShapeDrawable pitchButtonBackground = new ShapeDrawable(new RoundRectShape(
                            roundedCorners20, null, null));
                    pitchButtonBackground.getPaint().setColor(adjustedBackgroundColor);
                    pitchPresetButton.setBackground(pitchButtonBackground);
                    pitchPresetButton.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);

                    FrameLayout.LayoutParams pitchButtonParams = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, Dim.dp32, Gravity.CENTER);
                    pitchPresetButton.setLayoutParams(pitchButtonParams);

                    pitchPresetButton.setOnClickListener(v -> {
                        final float pitch = VideoInformation.getPlaybackAudioPitch();
                        final float newValue = switch (pitchLabel) {
                            case "/2" -> pitch * 0.5f;
                            case "−1st" -> (float) (pitch / ONE_SEMITONE);
                            case "1x" -> 1.0f;
                            case "+1st" -> (float) (pitch * ONE_SEMITONE);
                            case "×2" -> pitch * 2.0f;
                            default -> pitch;
                        };
                        userSelectedPitch.accept(newValue);
                    });
                    pitchButtonContainer.addView(pitchPresetButton);
                    pitchPresetGrid.addView(pitchButtonContainer);
                }

                // Pitch UI Layout Tree
                // Add the link button and its layout.
                linkLayout.addView(linkButton);
                contentLayout.addView(linkLayout);
                // Add Text to the label row with icon and text.
                pitchLabelRow.addView(currentPitchText);
                contentLayout.addView(pitchLabelRow);
                // Add -/+ and slider views to slider layout.
                pitchSliderLayout.addView(pitchMinusButton);
                pitchSliderLayout.addView(pitchSlider);
                pitchSliderLayout.addView(pitchPlusButton);
                // Add slider layout to content layout.
                contentLayout.addView(pitchSliderLayout);
                // Add in-rows pitch buttons layout to content layout.
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
    private static Button createStyledButton(Context context, boolean isPlus) {
        Button button = new Button(context, null, 0); // Disable default theme style.
        button.setText(""); // No text on button.
        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(20), null, null));
        background.getPaint().setColor(getAdjustedBackgroundColor(false));
        button.setBackground(background);
        button.setForeground(new OutlineSymbolDrawable(isPlus)); // Plus or minus symbol.
        ViewAnimations.applyPressEffect(button);
        final int size = Utils.appIsUsingBoldIcons() ? Dim.dp40 : Dim.dp36;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(Dim.dp8, 0, Dim.dp8, 0); // Set margins.
        button.setLayoutParams(params);
        return button;
    }

    /**
     * Creates a row containing a leading icon and a centered value label.
     * The icon is offset from the left edge without affecting the centered position of the value.
     *
     * @param context The Android context used to create the row.
     * @param iconId  The drawable resource id of the leading icon.
     * @return A configured {@link FrameLayout} containing the icon and centered value label.
     */
    private static FrameLayout createLabelRow(Context context, int iconId) {
        FrameLayout row = new FrameLayout(context);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp20, 0, 0);
        row.setLayoutParams(rowParams);

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
     * @return user speed converted to a value for {@link SeekBar#setProgress(int)}.
     */
    private static int speedToProgressValue(float speed) {
        return (int) ((speed - customPlaybackSpeedsMin) * PROGRESS_BAR_VALUE_SCALE);
    }

    /**
     * @return audio pitch converted to a value for {@link SeekBar#setProgress(int)}.
     */
    private static int pitchToProgressValue(float pitch) {
        return (int) ((pitch - customPlaybackSpeedsMin) * PROGRESS_BAR_VALUE_SCALE);
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
        paint.setColor(Utils.getAppForegroundColor());
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
