/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/** The artist-line template and the timing of its frames share one editor. */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsTrackFormatPreference extends Preference {
    public LyricsTrackFormatPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        label(content, "morphe_music_lyrics_mini_track_format_summary");
        EditText template = ThemedInput.create(context);
        template.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        template.setSingleLine(false);
        template.setMinLines(3);
        template.setMaxLines(6);
        template.setText(Settings.LYRICS_MINI_TRACK_FORMAT.get());
        content.addView(template);
        EditText minimum = numeric(content, "morphe_music_lyrics_mini_artist_min_label",
                Settings.LYRICS_MINI_ARTIST_MIN_MS.get());
        EditText maximum = numeric(content, "morphe_music_lyrics_mini_artist_max_label",
                Settings.LYRICS_MINI_ARTIST_MAX_MS.get());
        label(content, "morphe_music_lyrics_mini_artist_timing_explanation");

        Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str("morphe_music_lyrics_mini_track_format_title"), null, null,
                str("morphe_settings_save"), () -> {
                    try {
                        int min = Integer.parseInt(minimum.getText().toString().trim());
                        int max = Integer.parseInt(maximum.getText().toString().trim());
                        if (min < 0 || max <= 0 || max < min) throw new IllegalArgumentException();
                        Settings.LYRICS_MINI_ARTIST_MIN_MS.save(min);
                        Settings.LYRICS_MINI_ARTIST_MAX_MS.save(max);
                        Settings.LYRICS_MINI_TRACK_FORMAT.save(template.getText().toString());
                    } catch (IllegalArgumentException ex) {
                        Utils.showToastShort(str("morphe_music_lyrics_mini_artist_timing_invalid"));
                    }
                }, () -> {}, str("morphe_settings_reset"), () -> {
                    Settings.LYRICS_MINI_TRACK_FORMAT.resetToDefault();
                    Settings.LYRICS_MINI_ARTIST_MIN_MS.resetToDefault();
                    Settings.LYRICS_MINI_ARTIST_MAX_MS.resetToDefault();
                }, true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        pair.second.addView(scroll, pair.second.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        pair.first.show();
    }

    private void label(LinearLayout content, String key) {
        TextView label = new TextView(getContext());
        label.setText(str(key));
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setTextSize(14);
        label.setPadding(0, Dim.dp12, 0, Dim.dp8);
        content.addView(label);
    }

    private EditText numeric(LinearLayout content, String label, int value) {
        label(content, label);
        EditText input = ThemedInput.create(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(Integer.toString(value));
        content.addView(input);
        return input;
    }
}
