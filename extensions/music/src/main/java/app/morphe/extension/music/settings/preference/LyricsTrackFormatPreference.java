/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.music.settings.preference.LyricsFieldsDialog.text;
import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

/** Edits the track format and how long each of its frames is shown. */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsTrackFormatPreference extends Preference {
    public LyricsTrackFormatPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        LyricsFieldsDialog dialog = new LyricsFieldsDialog(getContext());
        EditText template = dialog.addInput("morphe_music_lyrics_mini_track_format_summary",
                Settings.LYRICS_MINI_TRACK_FORMAT.get(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        template.setSingleLine(false);
        template.setMinLines(3);
        template.setMaxLines(6);
        EditText minimum = dialog.addNumberInput("morphe_music_lyrics_mini_artist_min_label",
                Settings.LYRICS_MINI_ARTIST_MIN_MS.get(), false);
        EditText maximum = dialog.addNumberInput("morphe_music_lyrics_mini_artist_max_label",
                Settings.LYRICS_MINI_ARTIST_MAX_MS.get(), false);
        dialog.addLabel("morphe_music_lyrics_mini_artist_timing_explanation");

        dialog.show("morphe_music_lyrics_mini_track_format_title", () -> {
            final int min;
            final int max;
            try {
                min = Integer.parseInt(text(minimum));
                max = Integer.parseInt(text(maximum));
            } catch (NumberFormatException ex) {
                showInvalidToast();
                return;
            }
            if (min < 0 || max <= 0 || max < min) {
                showInvalidToast();
                return;
            }
            Settings.LYRICS_MINI_ARTIST_MIN_MS.save(min);
            Settings.LYRICS_MINI_ARTIST_MAX_MS.save(max);
            Settings.LYRICS_MINI_TRACK_FORMAT.save(template.getText().toString());
        }, () -> {
            Settings.LYRICS_MINI_TRACK_FORMAT.resetToDefault();
            Settings.LYRICS_MINI_ARTIST_MIN_MS.resetToDefault();
            Settings.LYRICS_MINI_ARTIST_MAX_MS.resetToDefault();
        });
    }

    private static void showInvalidToast() {
        Utils.showToastShort(str("morphe_music_lyrics_mini_artist_timing_invalid"));
    }
}
