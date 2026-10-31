/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;

/** One immutable start or restore request, separate from an attached native queue. */
record QueueStart(PlaylistQueue candidate, String previousVideoId, long resumePositionMilliseconds) {
    QueueStart(PlaylistQueue candidate, String previousVideoId) {
        this(candidate, previousVideoId, -1);
    }

    boolean restoresClosedPlayer() {
        return resumePositionMilliseconds >= 0;
    }

    String watchUrl() {
        // Match the native watch intent used by LoadVideoPatch's Reload action.
        return "https://www.youtube.com/watch?v=" + candidate.currentEntry().videoId()
                + (restoresClosedPlayer() ? "&t=" + resumePositionMilliseconds / 1000 + "s" : "");
    }

    boolean isSupersededBy(String videoId) {
        return PlaylistQueue.isVideoId(videoId) && !videoId.equals(previousVideoId)
                && !videoId.equals(candidate.currentEntry().videoId());
    }

    Map<?, ?> minimizedContext(MessageLite endpoint, Map<?, ?> context) throws InvalidProtocolBufferException {
        var command = NavigationEndpoint.parseFrom(endpoint.toByteArray());
        if (!command.hasWatchEndpoint()
                || !command.getWatchEndpoint().getVideoId().equals(candidate.currentEntry().videoId())) {
            return context;
        }

        Map<Object, Object> minimized = new HashMap<>();
        if (context != null) {
            minimized.putAll(context);
        }
        minimized.put("start_watch_minimized", true);
        return minimized;
    }
}
