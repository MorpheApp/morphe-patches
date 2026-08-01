/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2261
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.PlaybackInFeedsPatch;

/**
 * Shows and changes the 'Playback in feeds' mode used by YouTube.
 * The mode is stored by YouTube and not by Morphe, see {@link PlaybackInFeedsPatch}.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class PlaybackInFeedsListPreference extends CustomDialogListPreference {

    {
        // The mode can also be changed in the settings of YouTube,
        // so the value shown must be refreshed every time this preference is created.
        PlaybackInFeedsPatch.updateSettingFromApp();
    }

    public PlaybackInFeedsListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public PlaybackInFeedsListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PlaybackInFeedsListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlaybackInFeedsListPreference(Context context) {
        super(context);
    }

    @Override
    public void setValue(String value) {
        super.setValue(value);

        // Value is null if nothing is saved yet, since the settings framework
        // removes a preference key that is set to the default value.
        if (value != null) {
            PlaybackInFeedsPatch.setMode(Integer.parseInt(value));
        }
    }
}
