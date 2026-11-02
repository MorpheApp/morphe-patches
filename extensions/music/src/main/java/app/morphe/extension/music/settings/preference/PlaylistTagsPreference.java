/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.PlaylistTagsDialogs;

/**
 * Opens the playlist tags manager.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class PlaylistTagsPreference extends Preference {

    public PlaylistTagsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public PlaylistTagsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PlaylistTagsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlaylistTagsPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        PlaylistTagsDialogs.showManagerDialog(getContext());
    }
}
