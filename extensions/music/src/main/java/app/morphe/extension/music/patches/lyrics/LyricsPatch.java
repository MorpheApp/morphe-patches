/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Looper;

import app.morphe.extension.music.patches.lyrics.session.LockScreenLyrics;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

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
     * Injection point. Immediately freezes word-sync when MedialibPlayer pauses audio.
     */
    public static void onPauseVideo() {
        runOnMain(() -> LyricsManager.getInstance().onPauseVideo(), "onPauseVideo failure");
    }

    /**
     * Injection point. Immediately resumes word-sync when MedialibPlayer resumes audio.
     */
    public static void onPlayVideo() {
        runOnMain(() -> LyricsManager.getInstance().onPlayVideo(), "onPlayVideo failure");
    }

    private static void runOnMain(Runnable action, String failureLabel) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run();
            } else {
                Utils.runOnMainThread(action);
            }
        } catch (Exception ex) {
            Logger.printException(() -> failureLabel, ex);
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
        try {
            LockScreenLyrics.onPlaybackState(playbackState);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetPlaybackState lock screen failure", ex);
        }
    }
}
