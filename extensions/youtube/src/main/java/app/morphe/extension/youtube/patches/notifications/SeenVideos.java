/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Videos of the notifications that were played or shown as seen.
 * A video of a group of notifications has no dot once it is in here, even if the group gets a new video.
 * Videos played before they show up in the notifications are kept for a short time, Shorts in a list of their own.
 */
public final class SeenVideos {

    /**
     * Loaded on first use, which is never the main thread.
     */
    private static final class Store {
        private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

        private static final TimedVideoIds seen = new TimedVideoIds(
                "morphe_ungroup_notifications_seen", 2000, 90 * DAY_MILLIS);
        private static final TimedVideoIds notificationVideos = new TimedVideoIds(
                "morphe_ungroup_notifications_videos", 2000, 90 * DAY_MILLIS);
        private static final TimedVideoIds recentlyPlayed = new TimedVideoIds(
                "morphe_ungroup_notifications_played", 200, 14 * DAY_MILLIS);
        private static final TimedVideoIds recentlyPlayedShorts = new TimedVideoIds(
                "morphe_ungroup_notifications_played_shorts", 500, 3 * DAY_MILLIS);
        private static final TimedVideoIds shownUnseen = new TimedVideoIds(
                "morphe_ungroup_notifications_unseen", 2000, 90 * DAY_MILLIS);

        static {
            Logger.printDebug(() -> "Loaded " + seen.size() + " seen videos of "
                    + notificationVideos.size() + " notification videos");
        }

        private static synchronized void update(Collection<String> videoIds, Collection<String> seenVideoIds,
                                                Collection<String> unseenVideoIds) {
            notificationVideos.addAll(videoIds);
            seen.addAll(seenVideoIds);
            shownUnseen.addAll(unseenVideoIds);
            moveToSeen(recentlyPlayed, videoIds);
            moveToSeen(recentlyPlayedShorts, videoIds);
        }

        private static void moveToSeen(TimedVideoIds played, Collection<String> videoIds) {
            List<String> moved = new ArrayList<>();
            for (String videoId : videoIds) {
                if (played.contains(videoId)) {
                    moved.add(videoId);
                }
            }
            if (!moved.isEmpty()) {
                seen.addAll(moved);
                played.removeAll(moved);
            }
        }

        private static synchronized void addPlayed(String videoId, boolean isShort) {
            if (notificationVideos.contains(videoId)) {
                seen.add(videoId);
            } else {
                (isShort ? recentlyPlayedShorts : recentlyPlayed).add(videoId);
            }
        }
    }

    public static void addPlayedInBackground(@Nullable String videoId, boolean isShort) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        runInBackground(() -> Store.addPlayed(videoId, isShort));
    }

    /**
     * @param notificationVideoIds All videos of the notifications.
     * @param seenVideoIds The videos of them that were shown as seen.
     * @param unseenVideoIds The videos of them that were shown as unseen.
     */
    static void updateInBackground(Collection<String> notificationVideoIds, Collection<String> seenVideoIds,
                                   Collection<String> unseenVideoIds) {
        runInBackground(() -> Store.update(notificationVideoIds, seenVideoIds, unseenVideoIds));
    }

    /**
     * @return Whether the video was shown as unseen in the notifications before.
     */
    static boolean wasShownUnseen(@Nullable String videoId) {
        try {
            return videoId != null && Store.shownUnseen.contains(videoId);
        } catch (Throwable ex) {
            Logger.printException(() -> "SeenVideos failure", ex);
            return false;
        }
    }

    static boolean contains(@Nullable String videoId) {
        try {
            return videoId != null && (Store.seen.contains(videoId) || Store.recentlyPlayed.contains(videoId)
                    || Store.recentlyPlayedShorts.contains(videoId));
        } catch (Throwable ex) {
            Logger.printException(() -> "SeenVideos failure", ex);
            return false;
        }
    }

    private static void runInBackground(Runnable task) {
        Utils.runOnBackgroundThread(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                Logger.printException(() -> "SeenVideos failure", ex);
            }
        });
    }
}
