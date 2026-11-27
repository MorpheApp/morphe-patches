/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsWordCloudStopwordsPreference extends Preference {

    public LyricsWordCloudStopwordsPreference(Context context, AttributeSet attrs,
                                              int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setPersistent(false);
    }

    public LyricsWordCloudStopwordsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPersistent(false);
    }

    public LyricsWordCloudStopwordsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    public LyricsWordCloudStopwordsPreference(Context context) {
        super(context);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        LyricsFieldsDialog dialog = new LyricsFieldsDialog(getContext());
        EditText input = dialog.addInput("morphe_music_lyrics_word_cloud_stopwords_label",
                Settings.LYRICS_WORD_CLOUD_STOPWORDS.get(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setMaxLines(10);
        dialog.show("morphe_music_lyrics_word_cloud_stopwords_title",
                () -> Settings.LYRICS_WORD_CLOUD_STOPWORDS.save(input.getText().toString()),
                () -> Settings.LYRICS_WORD_CLOUD_STOPWORDS.resetToDefault());
    }
}
