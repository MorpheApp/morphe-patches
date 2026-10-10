/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.annotation.SuppressLint;
import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public final class CompletionPreference extends Preference {
    public CompletionPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
        setPersistent(false);
        summary();
    }

    private void summary() {
        setSummary(
                UiText.format("morphe_series_tracker_completion_summary",
                        CompletionPolicy.percent(Settings.SERIES_TRACKER_COMPLETION_PERCENT.get()),
                        CompletionPolicy.seconds(
                                Settings.SERIES_TRACKER_COMPLETION_SECONDS.get())));
    }

    @Override
    protected void onClick() {
        NativeSheet sheet = new NativeSheet(getContext(), "morphe_series_tracker_completion_title");
        sheet.message("morphe_series_tracker_completion_explanation");
        EditText percent =
                number(
                        sheet,
                        "morphe_series_tracker_completion_percent",
                        CompletionPolicy.percent(Settings.SERIES_TRACKER_COMPLETION_PERCENT.get()));
        EditText seconds =
                number(
                        sheet,
                        "morphe_series_tracker_completion_seconds",
                        CompletionPolicy.seconds(Settings.SERIES_TRACKER_COMPLETION_SECONDS.get()));
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        sheet.action(
                "morphe_series_tracker_completion_save",
                () -> {
                    Integer p = value(percent, 1, 100), s = value(seconds, 0, 300);
                    if (p == null || s == null) return;
                    Settings.SERIES_TRACKER_COMPLETION_PERCENT.save(p);
                    Settings.SERIES_TRACKER_COMPLETION_SECONDS.save(s);
                    summary();
                    sheet.dialog.dismiss();
                },
                true);
        sheet.show();
    }

    // The text is parsed back as a plain integer, so it must not use localized digits.
    @SuppressLint("SetTextI18n")
    private EditText number(NativeSheet sheet, String label, int value) {
        sheet.message(label);
        EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setTextColor(HistoryUi.foreground());
        input.setContentDescription(UiText.get(label));
        input.setText(Integer.toString(value));
        sheet.body.addView(input);
        return input;
    }

    private Integer value(EditText input, int min, int max) {
        String text = input.getText().toString().trim();
        if (text.matches("[0-9]{1,3}")) {
            int n = Integer.parseInt(text);
            if (n >= min && n <= max) return n;
        }
        input.setError(UiText.format("morphe_series_tracker_completion_range", min, max));
        return null;
    }
}
