/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LyricsPatch {

    private LyricsPatch() {
    }

    /**
     * Injection point.
     */
    public static void onSetMetadata(MediaMetadata metadata) {
        try {
            LyricsManager.getInstance().onSetMetadata(metadata);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onSetPlaybackState(PlaybackState playbackState) {
        try {
            LyricsManager.getInstance().onSetPlaybackState(playbackState);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetPlaybackState failure", ex);
        }
    }

    
}
