/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import app.morphe.extension.shared.Logger;

public class ScrobbleHook {
    public static void onSetMetadata(MediaMetadata metadata) {
        try {
            ScrobbleManager.getInstance().onSetMetadata(metadata);
        } catch (Throwable t) {
            Logger.printException(() -> "ScrobbleHook: onSetMetadata failed", t);
        }
    }

    public static void onSetPlaybackState(PlaybackState state) {
        try {
            ScrobbleManager.getInstance().onSetPlaybackState(state);
        } catch (Throwable t) {
            Logger.printException(() -> "ScrobbleHook: onSetPlaybackState failed", t);
        }
    }
}
