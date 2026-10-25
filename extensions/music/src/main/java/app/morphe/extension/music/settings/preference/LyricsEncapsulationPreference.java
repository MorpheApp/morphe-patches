/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ResettableEditTextPreference;

/**
 * Lyrics encapsulation template, which is only saved if it has a place for the lyric.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsEncapsulationPreference extends ResettableEditTextPreference {

    public LyricsEncapsulationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public LyricsEncapsulationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LyricsEncapsulationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LyricsEncapsulationPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        if (!MiniLyricsFormatter.validEncapsulation(String.valueOf(newValue))) {
            Utils.showToastShort(str("morphe_music_lyrics_mini_encapsulation_invalid"));
            return false;
        }
        return super.callChangeListener(newValue);
    }
}
