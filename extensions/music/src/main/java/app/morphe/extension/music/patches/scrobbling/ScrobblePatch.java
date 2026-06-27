/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;

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
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    ScrobbleManager.getInstance().onLikeClicked(serviceName, videoId);
                } catch (Throwable t) {
                    Logger.printException(() -> "ScrobbleHook: onLikeClicked inside post failed", t);
                }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "ScrobbleHook: onLikeClicked failed", t);
        }
    }
}

