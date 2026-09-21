package app.morphe.extension.youtube.series;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.youtube.patches.LoadVideoPatch;

public final class ResumeLauncher {
    public static Intent intent(Context context, LaunchRequest request) {
        Uri.Builder uri =
                new Uri.Builder()
                        .scheme("https")
                        .authority("www.youtube.com")
                        .path("watch")
                        .appendQueryParameter("v", request.videoId);
        if (!request.playlistId.isEmpty()) uri.appendQueryParameter("list", request.playlistId);
        // Include zero explicitly: omitting it can restore YouTube's own remembered position.
        uri.appendQueryParameter("t", request.seconds() + "s");
        return new Intent(Intent.ACTION_VIEW, uri.build())
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void launch(Context context, LaunchRequest request) {

        Intent launch = intent(context, request);
        TrackerRuntime.prepareLaunch();
        TrackerRuntime.awaitLaunch(request);
        try {
            String active;
            try {
                active = PlaybackBridge.videoId();
            } catch (RuntimeException ignored) {
                active = "";
            }
            if (request.videoId.equals(active)) {
                // Measured on 21.13.164: an ordinary watch intent leaves a paused
                // same-video player at its old timestamp. Recreate it via the host.

                LoadVideoPatch.openVideoIntent(launch.getDataString(), true);
            } else {
                context.startActivity(launch);
            }
        } catch (RuntimeException failure) {
            TrackerRuntime.cancelLaunch();
            throw failure;
        }
    }

    private ResumeLauncher() {}
}
