/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.net.Uri;

import app.morphe.extension.youtube.patches.LoadVideoPatch;

public final class ResumeLauncher {
    private static String url(LaunchRequest request) {
        Uri.Builder uri = new Uri.Builder()
                .scheme("https")
                .authority("www.youtube.com")
                .path("watch")
                .appendQueryParameter("v", request.videoId());
        if (!request.playlistId().isEmpty()) uri.appendQueryParameter("list", request.playlistId());
        // Include zero explicitly: omitting it can restore YouTube's own remembered position.
        uri.appendQueryParameter("t", request.seconds() + "s");
        return uri.build().toString();
    }

    static void launch(LaunchRequest request) {
        TrackerRuntime.prepareLaunch();
        TrackerRuntime.awaitLaunch(request);
        // Measured on 21.13.164: an ordinary watch intent leaves a paused
        // same-video player at its old timestamp. Recreate it via the host.
        final boolean closeCurrentPlayer = request.videoId().equals(PlaybackBridge.videoId());
        if (!LoadVideoPatch.openUrl(url(request), closeCurrentPlayer)) {
            TrackerRuntime.cancelLaunch();
            throw new IllegalStateException("Could not open video: " + request.videoId());
        }
    }

    private ResumeLauncher() {}
}
