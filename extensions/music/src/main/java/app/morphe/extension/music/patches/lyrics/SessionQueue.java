/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaDescription;
import android.media.session.MediaSession;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.session.LockScreenLyrics;
import app.morphe.extension.shared.Logger;

/**
 * Reads the track that plays after a given one from the queue the app published to its own
 * media session.
 *
 * <p>The media session is part of the platform, so no other patch is needed: whatever the app
 * hands the system, the controller of that same session hands back; an app that publishes
 * none yields nothing here. Reading it crosses the session binder, so it blocks and must not
 * run on the main thread.
 */
final class SessionQueue {

    private SessionQueue() {
    }

    /**
     * Matching is by item media id, so the answer stays right while the session catches up
     * with a switch. The item after the last is the first - where autoplay and single-track
     * repeat land. {@code videoIdOut} receives the successor's media id: equal to the playing
     * id it is a repeat, any other value is only a title to prefetch.
     */
    @Nullable
    static TrackInfo nextTrackOf(@NonNull String currentVideoId,
                                 @NonNull String[] videoIdOut) {
        List<MediaSession.QueueItem> items = queueItems();
        if (items.isEmpty()) return null;

        MediaSession.QueueItem next = null;
        for (int index = 0; index < items.size(); index++) {
            MediaSession.QueueItem item = items.get(index);
            if (item == null) continue;
            if (currentVideoId.equals(mediaIdOf(item))) {
                next = items.get((index + 1) % items.size());
                break;
            }
        }
        // The playing video is not in the queue, so the first item is all there is.
        if (next == null) {
            next = items.get(0);
        }
        return trackOf(next, videoIdOut);
    }

    @NonNull
    private static List<MediaSession.QueueItem> queueItems() {
        try {
            MediaSession session = LockScreenLyrics.mediaSession();
            if (session == null) return List.of();
            List<MediaSession.QueueItem> items = session.getController().getQueue();
            if (items == null || items.isEmpty()) {
                Logger.printDebug(() -> "MediaSession publishes no queue: "
                        + "successor lyrics are not prefetched");
                return List.of();
            }
            Logger.printDebug(() -> "MediaSession queue holds " + items.size() + " items");
            return items;
        } catch (Throwable ex) {
            Logger.printException(() -> "Reading the media session queue failed", ex);
            return List.of();
        }
    }

    @Nullable
    private static TrackInfo trackOf(@Nullable MediaSession.QueueItem item,
                                     @NonNull String[] videoIdOut) {
        if (item == null) return null;

        videoIdOut[0] = mediaIdOf(item);
        MediaDescription description = item.getDescription();
        String rawTitle = textOf(description != null ? description.getTitle() : null);
        String rawArtist = textOf(description != null ? description.getSubtitle() : null);
        return QueueTracks.trackOf(rawTitle, rawArtist);
    }

    /** The description strings arrive as any character sequence, or not at all. */
    @Nullable
    private static String textOf(@Nullable CharSequence text) {
        return text != null ? text.toString() : null;
    }

    @Nullable
    private static String mediaIdOf(@Nullable MediaSession.QueueItem item) {
        if (item == null) return null;
        MediaDescription description = item.getDescription();
        return description != null ? description.getMediaId() : null;
    }
}