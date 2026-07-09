/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;
import static app.morphe.extension.youtube.settings.Settings.DO_NOT_REMEMBER_LOOP_VIDEO;
import static app.morphe.extension.youtube.settings.Settings.LOOP_VIDEO_BUTTON;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.LoopVideoPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LoopVideoButton {

    static {
        if (Settings.LOOP_VIDEO_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static final int LOOP_VIDEO_ON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            RESTORE_OLD_PLAYER_BUTTONS
                    ? "morphe_loop_video_button_on"
                    : "morphe_loop_video_button_on_bold");
    private static final int LOOP_VIDEO_OFF = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            RESTORE_OLD_PLAYER_BUTTONS
                    ? "morphe_loop_video_button_off"
                    : "morphe_loop_video_button_off_bold");
    private static final int LOOP_VIDEO_RANGE = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            RESTORE_OLD_PLAYER_BUTTONS
                    ? "morphe_loop_video_button_range"
                    : "morphe_loop_video_button_range_bold");
    private static final String videoRangeInvalidTimeStringName =
            "morphe_loop_video_range_invalid_time";

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_loop_video_button",
                    null,
                    null,
                    Settings.LOOP_VIDEO_BUTTON::get,
                    LoopVideoButton::handleShortClick,
                    v -> {
                        showRangeDialog(v.getContext());
                        return true;
                    }
            );
            updateButtonIcon();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    public static void setLoopButton(boolean newState) {
        Settings.LOOP_VIDEO.save(newState);

        if (!newState) {
            LoopVideoPatch.clearRange();
        }
    }

    /**
     * Injection point.
     */
    public static void resetLoopButton() {
        if (LOOP_VIDEO_BUTTON.get() && DO_NOT_REMEMBER_LOOP_VIDEO.get()) {
            setLoopButton(false);
        }
    }



    /**
     * Short click: toggle loop on/off. Turning off also clears any active range.
     */
    private static void handleShortClick(View buttonView) {
        if (legacy == null) return;
        Utils.verifyOnMainThread();

        final boolean newState = !Settings.LOOP_VIDEO.get();
        setLoopButton(newState);

        Utils.showToastShort(str(newState
                ? "morphe_loop_video_button_toast_on"
                : "morphe_loop_video_button_toast_off"));

        animateButtonTransition(buttonView, getTargetIcon());
    }

    /**
     * Long click: open dialog to configure loop range.
     */
    private static void showRangeDialog(Context context) {
        try {
            final long currentTime = VideoInformation.getVideoTime();
            final long videoLength = VideoInformation.getVideoLength();

            final boolean rangeActive = LoopVideoPatch.isRangeActive();
            final String startPrefill = rangeActive
                    ? formatTime(LoopVideoPatch.rangeStartMs)
                    : (currentTime > 0 ? formatTime(currentTime) : "");
            final String endPrefill = rangeActive
                    ? formatTime(LoopVideoPatch.rangeEndMs)
                    : "";

            final EditText startField = buildTimeEditText(context,
                    str("morphe_loop_video_range_hint_start"),
                    startPrefill);

            final EditText endField = buildTimeEditText(context,
                    str("morphe_loop_video_range_hint_end"),
                    endPrefill);

            Pair<Dialog, LinearLayout> result = CustomDialog.create(
                    context,
                    str("morphe_loop_video_range_dialog_title"),
                    null,
                    null,
                    str("morphe_loop_video_range_set"),
                    () -> applyRange(startField.getText().toString(),
                            endField.getText().toString(), videoLength),
                    () -> { /* dismiss only */ },
                    str("morphe_loop_video_range_clear"),
                    () -> {
                        LoopVideoPatch.clearRange();
                        Settings.LOOP_VIDEO.save(false);
                        onRangeCleared();
                    },
                    true
            );

            Dialog dialog = result.first;
            LinearLayout mainLayout = result.second;

            // Insert the two time fields between title (index 0) and buttons (index 1).
            mainLayout.addView(buildFieldsLayout(context, startField, endField), 1);

            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showRangeDialog failure", ex);
        }
    }

    private static void applyRange(String startInput, String endInput, long videoLengthMs) {
        final long startMs = parseTime(startInput);
        if (startMs < 0) {
            Utils.showToastShort(str(videoRangeInvalidTimeStringName));
            return;
        }

        final long endMs;
        boolean endIsVideoEnd = endInput.trim().isEmpty();
        if (endIsVideoEnd) {
            if (videoLengthMs <= 0) {
                Utils.showToastShort(str(videoRangeInvalidTimeStringName));
                return;
            }
            endMs = videoLengthMs;
        } else {
            final long parsed = parseTime(endInput);
            if (parsed < 0) {
                Utils.showToastShort(str(videoRangeInvalidTimeStringName));
                return;
            }
            if (videoLengthMs > 0 && parsed >= videoLengthMs) {
                endMs = videoLengthMs;
                endIsVideoEnd = true;
            } else {
                endMs = parsed;
            }
        }

        if (endMs <= startMs) {
            Utils.showToastShort(str(videoRangeInvalidTimeStringName));
            return;
        }

        // Resets the loop range state, if set, to avoid possible errors
        if (!LoopVideoPatch.rangeVideoId.isEmpty()) {
            setLoopButton(false);
        }

        LoopVideoPatch.setRange(startMs, endMs, endIsVideoEnd);
        Settings.LOOP_VIDEO.save(true);
        Utils.showToastShort(str("morphe_loop_video_range_toast_on",
                formatTime(startMs), formatTime(endMs)));
        updateButtonIcon();
    }

    private static int getTargetIcon() {
        if (!Settings.LOOP_VIDEO.get()) return LOOP_VIDEO_OFF;
        if (LoopVideoPatch.isRangeActive()) return LOOP_VIDEO_RANGE;
        return LOOP_VIDEO_ON;
    }

    private static void updateButtonIcon() {
        LegacyPlayerControlButton localInstance = legacy;
        if (localInstance == null) return;
        localInstance.setIcon(getTargetIcon());
    }

    /**
     * Called when the range is cleared externally (video change or "Clear" button).
     */
    public static void onRangeCleared() {
        Utils.runOnMainThread(LoopVideoButton::updateButtonIcon);
    }

    private static void animateButtonTransition(View buttonView, int newIcon) {
        LegacyPlayerControlButton localInstance = legacy;
        if (localInstance == null) return;

        if (!(buttonView instanceof ImageView imageView)) {
            localInstance.setIcon(newIcon);
            return;
        }

        imageView.animate()
                .alpha(0.3f)
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(100)
                .withEndAction(() -> {
                    localInstance.setIcon(newIcon);
                    imageView.animate()
                            .alpha(1.0f)
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100)
                            .start();
                })
                .start();
    }

    /**
     * Injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacy != null) legacy.setVisibilityNegatedImmediate();
    }

    /**
     * Injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacy != null) legacy.setVisibilityImmediate(visible);
        if (visible) updateButtonIcon();
    }

    /**
     * Injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacy != null) legacy.setVisibility(visible, animated);
        if (visible) updateButtonIcon();
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /**
     * @return milliseconds, or -1 if the input is not a valid time string.
     */
    private static long parseTime(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return -1;
        String[] parts = trimmed.split(":");
        try {
            long totalSeconds;
            if (parts.length == 1) {
                // Bare digits without colon — treat as seconds (e.g. "30" → 30s).
                totalSeconds = Long.parseLong(parts[0]);
            } else if (parts.length == 2) {
                totalSeconds = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                totalSeconds = Long.parseLong(parts[0]) * 3600
                        + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2]);
            } else {
                return -1;
            }
            return totalSeconds * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static EditText buildTimeEditText(Context context, String hint, String prefilledText) {
        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        // Override the DigitsKeyListener that TYPE_CLASS_NUMBER installs — it strips ':',
        // which breaks the TextWatcher colon auto-insertion.
        editText.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789:"));
        editText.setTextColor(Utils.getAppForegroundColor());
        editText.setHintTextColor(Color.GRAY);
        editText.setBackgroundColor(Color.TRANSPARENT);
        editText.setPadding(0, 0, 0, 0);
        addAutoColonWatcher(editText);
        if (!prefilledText.isEmpty()) {
            editText.setText(prefilledText);
            editText.setSelection(editText.getText().length());
        }
        return editText;
    }

    private static void addAutoColonWatcher(EditText editText) {
        editText.addTextChangedListener(new android.text.TextWatcher() {
            private boolean isFormatting = false;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (isFormatting) return;
                isFormatting = true;
                try {
                    String digits = s.toString().replaceAll("[^0-9]", "");
                    if (digits.length() > 6) digits = digits.substring(0, 6);
                    String formatted = formatDigitsAsTime(digits);
                    s.replace(0, s.length(), formatted);
                    android.text.Selection.setSelection(s, formatted.length());
                } finally {
                    isFormatting = false;
                }
            }
        });
    }

    private static String formatDigitsAsTime(String d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length(); i++) {
            if (i == 2 || i == 4) sb.append(':');
            sb.append(d.charAt(i));
        }
        return sb.toString();
    }

    private static LinearLayout buildFieldsLayout(Context context, EditText startField, EditText endField) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(buildLabeledField(context,
                str("morphe_loop_video_range_label_start"), startField, true));
        container.addView(buildLabeledField(context,
                str("morphe_loop_video_range_label_end"), endField, false));

        return container;
    }

    private static LinearLayout buildLabeledField(Context context, String label, EditText field, boolean isFirst) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        columnParams.setMargins(0, isFirst ? Dim.dp8 : Dim.dp16, 0, 0);
        column.setLayoutParams(columnParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(13);
        labelView.setTextColor(Utils.getAppForegroundColor());
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(Dim.dp4, 0, 0, Dim.dp4);
        labelView.setLayoutParams(labelParams);

        ShapeDrawable fieldBackground = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        fieldBackground.getPaint().setColor(Utils.getEditTextBackground());

        LinearLayout fieldBox = new LinearLayout(context);
        fieldBox.setBackground(fieldBackground);
        fieldBox.setClipToOutline(true);
        fieldBox.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        fieldBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        fieldBox.addView(field);

        column.addView(labelView);
        column.addView(fieldBox);
        return column;
    }
}
