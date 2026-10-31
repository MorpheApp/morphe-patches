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
import android.util.AttributeSet;
import android.widget.EditText;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

/** Edits both line limits and their shared full-width character weight together. */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsLineLimitsPreference extends Preference {
    private static final int MAX_CHAR_LIMIT = 10000;

    public LyricsLineLimitsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        LyricsFieldsDialog dialog = new LyricsFieldsDialog(getContext());
        EditText lyrics = dialog.addNumberInput("morphe_music_lyrics_mini_line_limits_lyrics",
                Settings.LYRICS_MINI_CHAR_LIMIT.get(), false);
        EditText artist = dialog.addNumberInput("morphe_music_lyrics_mini_line_limits_artist",
                Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.get(), false);
        EditText weight = dialog.addNumberInput("morphe_music_lyrics_mini_line_limits_weight",
                Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.get(), true);

        dialog.show("morphe_music_lyrics_mini_line_limits_title", () -> {
            final int lyricLimit;
            final int artistLimit;
            final float multiplier;
            try {
                lyricLimit = Integer.parseInt(text(lyrics));
                artistLimit = Integer.parseInt(text(artist));
                multiplier = Float.parseFloat(text(weight));
            } catch (NumberFormatException ex) {
                showInvalidToast();
                return;
            }
            if (!isValidLimit(lyricLimit) || !isValidLimit(artistLimit)
                    || !Float.isFinite(multiplier) || multiplier <= 0) {
                showInvalidToast();
                return;
            }
            Settings.LYRICS_MINI_CHAR_LIMIT.save(lyricLimit);
            Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.save(artistLimit);
            Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.save(multiplier);
        }, () -> {
            Settings.LYRICS_MINI_CHAR_LIMIT.resetToDefault();
            Settings.LYRICS_MINI_ARTIST_CHAR_LIMIT.resetToDefault();
            Settings.LYRICS_MINI_FULL_WIDTH_MULTIPLIER.resetToDefault();
        });
    }

    private static boolean isValidLimit(int limit) {
        return limit >= 0 && limit <= MAX_CHAR_LIMIT;
    }

    private static void showInvalidToast() {
        Utils.showToastShort(str("morphe_music_lyrics_mini_line_limits_invalid"));
    }
}
