/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class WatchLaterPlaylistPreference extends Preference {
    public WatchLaterPlaylistPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        String title = Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_TITLE.get();
        if (!title.isEmpty()) setSummary(title);
    }

    @Override
    protected void onClick() {
        PlaylistPatch.showPlaylistPicker(getContext(), "WL", (id, title) -> {
            Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.save("WL".equals(id) ? "" : id);
            Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_TITLE.save(title);
            setSummary(title);
        });
    }
}
