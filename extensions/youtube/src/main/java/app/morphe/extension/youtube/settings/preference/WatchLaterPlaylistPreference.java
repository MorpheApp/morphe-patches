/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.SaveToWatchLaterPatch.WATCH_LATER_PLAYLIST_ID;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Selects the playlist that Watch later saves are added to.
 */
@SuppressWarnings({"unused", "deprecation"})
public class WatchLaterPlaylistPreference extends Preference {

    public WatchLaterPlaylistPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        String title = Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_TITLE.get();
        if (!title.isEmpty()) {
            setSummary(title);
        }
    }

    @Override
    protected void onClick() {
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastShort(str("morphe_queue_manager_check_failed_auth"));
            return;
        }

        PlaylistPatch.showPlaylistPicker(getContext(), WATCH_LATER_PLAYLIST_ID, (id, title) -> {
            if (WATCH_LATER_PLAYLIST_ID.equals(id)) {
                Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.resetToDefault();
            } else {
                Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_ID.save(id);
            }
            Settings.SAVE_TO_WATCH_LATER_REDIRECT_PLAYLIST_TITLE.save(title);
            setSummary(title);
        });
    }
}
