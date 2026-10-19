/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.lyrics.romanizers.CantoneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;

/**
 * The notes on what the lyrics patch does and where it looks, with whatever the reading
 * dictionaries ask to be shown below them. Their licences travel into the cache with the data
 * and are read back from there, so nothing appears until one has been fetched.
 */
public class LyricsAboutPreference extends Preference {

    public LyricsAboutPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsAboutPreference(Context context) {
        super(context);
        init();
    }

    /** Written here rather than in XML, since what the dictionaries add is not known
     * when the layout is built. */
    private void init() {
        setPersistent(false);
        CharSequence notices = notices();
        setSummary(notices.length() > 0
                ? TextUtils.concat(str("morphe_music_lyrics_about_summary"), "\n\n", notices)
                : str("morphe_music_lyrics_about_summary"));
    }

    private static CharSequence notices() {
        StringBuilder joined = new StringBuilder();
        for (String notice : new String[] {
                PinyinDictStore.notice(), CantoneseDictStore.notice(), JapaneseDictStore.notice(),
                PinyinDictStore.wordsNotice(), CantoneseDictStore.wordsNotice()
        }) {
            if (notice == null) continue;
            if (joined.length() > 0) joined.append("\n\n");
            joined.append(notice);
        }
        return joined;
    }
}
