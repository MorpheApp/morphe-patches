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

/** Edits both line limits and their shared character weight together. */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsLineLimitsPreference extends Preference {
    public LyricsLineLimitsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        EditText lyrics = input(content, "morphe_music_lyrics_mini_line_limits_lyrics",
                Settings.LYRICS_MINI_CHAR_LIMIT.get().toString(), false);
        EditText artist = input(content, "morphe_music_lyrics_mini_line_limits_artist",
                Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.get().toString(), false);
        EditText weight = input(content, "morphe_music_lyrics_mini_line_limits_weight",
                Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.get().toString(), true);

        Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str("morphe_music_lyrics_mini_line_limits_title"), null, null,
                str("morphe_settings_save"), () -> {
                    try {
                        int lyricLimit = Integer.parseInt(lyrics.getText().toString().trim());
                        int artistLimit = Integer.parseInt(artist.getText().toString().trim());
                        float multiplier = Float.parseFloat(weight.getText().toString().trim());
                        if (lyricLimit < 0 || lyricLimit > 10000 || artistLimit < 0 || artistLimit > 10000
                                || !Float.isFinite(multiplier) || multiplier <= 0) {
                            throw new IllegalArgumentException();
                        }
                        Settings.LYRICS_MINI_CHAR_LIMIT.save(lyricLimit);
                        Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.save(artistLimit);
                        Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.save(multiplier);
                    } catch (IllegalArgumentException ex) {
                        Utils.showToastShort(str("morphe_music_lyrics_mini_line_limits_invalid"));
                    }
                }, () -> {}, str("morphe_settings_reset"), () -> {
                    Settings.LYRICS_MINI_CHAR_LIMIT.resetToDefault();
                    Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.resetToDefault();
                    Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.resetToDefault();
                }, true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        pair.second.addView(scroll, pair.second.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        pair.first.show();
    }

    private EditText input(LinearLayout content, String labelKey, String value, boolean decimal) {
        TextView label = new TextView(getContext());
        label.setText(str(labelKey));
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setTextSize(14);
        label.setPadding(0, Dim.dp12, 0, Dim.dp8);
        content.addView(label);
        EditText input = ThemedInput.create(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        input.setText(value);
        content.addView(input);
        return input;
    }
}
