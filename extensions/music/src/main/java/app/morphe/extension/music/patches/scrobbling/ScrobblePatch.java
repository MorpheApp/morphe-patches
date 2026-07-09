/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class ScrobblePatch {
    /**
     * Injection point.
     */
    public static void onSetMetadata(MediaMetadata metadata) {
        try {
            ScrobbleManager.getInstance().onSetMetadata(metadata);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onSetPlaybackState(PlaybackState state) {
        try {
            ScrobbleManager.getInstance().onSetPlaybackState(state);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetPlaybackState failure", ex);
        }
    }

    public static void onLikeClicked(final String serviceName, final String videoId) {
        // Edit: If this call is always on the main thread then runOnMainThreadNowOrLater can be removed.
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                ScrobbleManager.getInstance().onLikeClicked(serviceName, videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "onLikeClicked failure", ex);
            }
        });
    }
}

