/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ResettableEditTextPreference;

/** Native resettable preference with validation before any setting is saved. */
@SuppressWarnings({"unused", "deprecation"})
public final class LyricsPresentationPreference extends ResettableEditTextPreference {
    public LyricsPresentationPreference(Context context) { super(context); }
    public LyricsPresentationPreference(Context context, AttributeSet attrs) { super(context, attrs); }
    public LyricsPresentationPreference(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
    }
    public LyricsPresentationPreference(Context context, AttributeSet attrs, int style, int res) {
        super(context, attrs, style, res);
    }

    @Override
    protected void showDialog(Bundle state) {
        if (Settings.LYRICS_MINI_INSTRUMENTAL.key.equals(getKey())) {
            getEditText().setSingleLine(false);
            getEditText().setMinLines(3);
            getEditText().setMaxLines(8);
            getEditText().setVerticalScrollBarEnabled(true);
        }
        if (Settings.LYRICS_MINI_NEXT_OFFSET_MS.key.equals(getKey())) {
            getEditText().setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        super.showDialog(state);
    }

    @Override
    protected boolean callChangeListener(Object value) {
        final String text = String.valueOf(value);
        if (Settings.LYRICS_MINI_ENCAPSULATION.key.equals(getKey())
                && !MiniLyricsFormatter.validEncapsulation(text)) {
            Utils.showToastShort(str("morphe_music_lyrics_mini_encapsulation_invalid"));
            return false;
        }
        if (Settings.LYRICS_MINI_CHAR_LIMIT.key.equals(getKey())) {
            try {
                final int limit = Integer.parseInt(text);
                if (limit < 0 || limit > 10000) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                Utils.showToastShort(str("morphe_music_lyrics_mini_char_limit_invalid"));
                return false;
            }
        }
        if (Settings.LYRICS_MINI_NEXT_OFFSET_MS.key.equals(getKey())) {
            try {
                final int offset = Integer.parseInt(text.trim());
                if (offset < -10000 || offset > 10000) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                Utils.showToastShort(str("morphe_music_lyrics_mini_next_offset_invalid"));
                return false;
            }
        }
        return super.callChangeListener(value);
    }
}
