/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2753
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestreams;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Remembers the playback position of ongoing livestreams.
 * <p>
 * An ongoing livestream is detected by its reported duration growing in real time
 * (the duration of a regular video never changes while watching). Streams without
 * a growing duration have no DVR seeking available, so restoring is not possible
 * for them anyway. For detected livestreams the last playback position is
 * periodically saved and restored the next time the same livestream is opened.
 */
@SuppressWarnings("unused")
public final class RememberLivestreamPositionPatch {

    /**
     * Minimum duration growth to confirm a video is an ongoing livestream while watching.
     */
    private static final long LIVESTREAM_DURATION_GROWTH_WHILE_WATCHING_MS = 3000;

    /**
     * Minimum duration growth (compared to the saved value) to confirm the stream
     * is still ongoing when reopening it.
     */
    private static final long LIVESTREAM_DURATION_GROWTH_ON_REOPEN_MS = 1000;

    /**
     * If the saved position is closer than this to the end of the stream at save time,
     * the livestream was being watched at the live edge.
     */
    private static final long LIVE_EDGE_THRESHOLD_MS = 30_000;

    /**
     * How often the playback position is saved while watching an ongoing livestream.
     */
    private static final long SAVE_INTERVAL_MS = 5000;

    /**
     * Delay between checks when trying to restore a saved position.
     */
    private static final long RESTORE_POLL_DELAY_MS = 500;

    /**
     * Maximum number of restore checks. Roughly 20 seconds, which is enough time
     * for the duration to grow past the saved value after quickly closing and reopening.
     */
    private static final int RESTORE_MAX_ATTEMPTS = 40;

    /**
     * Delay between seek attempts when restoring.
     */
    private static final long RESTORE_SEEK_RETRY_DELAY_MS = 1000;

    /**
     * Maximum number of seek attempts. The player sometimes ignores very early
     * seek calls (while it is still loading), so the seek is retried until the
     * playback time matches the target.
     */
    private static final int RESTORE_SEEK_MAX_ATTEMPTS = 10;

    /**
     * How close the playback time must be to the target to consider a restore seek successful.
     */
    private static final long RESTORE_SEEK_TOLERANCE_MS = 3000;

    /**
     * Maximum number of saved streams kept. Prevents unbounded growth of the settings storage.
     */
    private static final int MAX_SAVED_STREAMS = 100;

    /**
     * Duration of the current video when first observed. Zero if not yet observed.
     */
    private static long baselineVideoLength;

    /**
     * True if the current video was confirmed to be an ongoing livestream.
     */
    private static boolean livestreamConfirmed;

    private static long lastSaveTime;

    /**
     * Incremented on every new video, used to cancel pending restore checks.
     */
    private static long newVideoGeneration;

    private static int restoreAttempts;

    /**
     * True while a saved position is waiting to be restored for the current video.
     * While pending, saving is suppressed, otherwise the live playback position
     * would overwrite the saved position before it can be restored.
     */
    private static boolean restorePending;

    @Nullable
    private static Map<String, SavedPosition> savedPositions;

    private record SavedPosition(long position, long videoLength, long timestamp) {
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        try {
            Utils.verifyOnMainThread();
            final boolean settingEnabled = Settings.REMEMBER_LIVESTREAM_POSITION.get();
            final boolean resumeWhenLive = Settings.REMEMBER_LIVESTREAM_POSITION_RESUME_WHEN_LIVE.get();

            baselineVideoLength = 0;
            livestreamConfirmed = false;
            lastSaveTime = 0;
            restoreAttempts = 0;
            restorePending = false;
            newVideoGeneration++;

            if (!settingEnabled) {
                return;
            }

            restorePending = true;
            startRestoreCheck(newVideoGeneration);
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoStarted failure", ex);
        }
    }

    /**
     * Injection point. Called approximately once per second during playback.
     */
    public static void videoTimeChanged(long playbackTimeMs) {
        try {
            if (!Settings.REMEMBER_LIVESTREAM_POSITION.get()) {
                return;
            }
            if (playbackTimeMs <= 0) {
                return;
            }
            if (restorePending) {
                // A saved position is waiting to be restored. Do not save anything,
                // otherwise the live position would overwrite the position to restore.
                return;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) {
                // Player is not fully loaded yet.
                return;
            }

            if (baselineVideoLength == 0) {
                baselineVideoLength = videoLength;
                return;
            }

            if (!livestreamConfirmed) {
                // The duration of an ongoing livestream grows in real time,
                // while the duration of a regular video never changes.
                if (videoLength - baselineVideoLength < LIVESTREAM_DURATION_GROWTH_WHILE_WATCHING_MS) {
                    return;
                }
                livestreamConfirmed = true;
                Logger.printDebug(() -> "Detected ongoing livestream len: " + videoLength
                        + " base: " + baselineVideoLength);
            }

            final long now = System.currentTimeMillis();
            if (now - lastSaveTime < SAVE_INTERVAL_MS) {
                return;
            }
            lastSaveTime = now;

            savePlaybackPosition(VideoInformation.getVideoId(), playbackTimeMs, videoLength);
        } catch (Exception ex) {
            Logger.printException(() -> "videoTimeChanged failure", ex);
        }
    }

    private static void startRestoreCheck(long generation) {
        Utils.runOnMainThreadDelayed(() -> checkRestore(generation), RESTORE_POLL_DELAY_MS);
    }

    private static void checkRestore(long generation) {
        try {
            if (generation != newVideoGeneration) {
                // Another video started meanwhile.
                return;
            }

            String videoId = VideoInformation.getVideoId();
            if (videoId.isEmpty()) {
                // Video id not available yet.
                rescheduleOrGiveUp(generation);
                return;
            }

            SavedPosition saved = loadPlaybackPosition(videoId);
            if (saved == null) {
                // Nothing remembered for this video. Allow saving again.
                restorePending = false;
                return;
            }

            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0 || videoLength < saved.videoLength + LIVESTREAM_DURATION_GROWTH_ON_REOPEN_MS) {
                // The duration has not yet grown beyond the saved value, so it is not yet
                // confirmed the stream is still ongoing. Keep checking for a while.
                rescheduleOrGiveUp(generation);
                return;
            }

            // The stream is still ongoing and advanced since it was last watched.
            final boolean watchedAtLiveEdge = saved.videoLength - saved.position < LIVE_EDGE_THRESHOLD_MS;
            Logger.printDebug(() -> "CheckRestore id: " + videoId
                    + " savedPos: " + saved.position + " savedLen: " + saved.videoLength
                    + " curLen: " + videoLength + " liveEdge: " + watchedAtLiveEdge);

            if (!watchedAtLiveEdge || Settings.REMEMBER_LIVESTREAM_POSITION_RESUME_WHEN_LIVE.get()) {
                // Delete the saved position only after the seek is confirmed (or given up on).
                attemptRestoreSeek(generation, videoId, saved.position, 1);
            } else {
                // Watched at the live edge and resuming is disabled. Consume the position.
                Logger.printDebug(() -> "Video was watched live, seeking to the play head");
                restorePending = false;
                deletePlaybackPosition(videoId);
            }
        } catch (Exception ex) {
            restorePending = false;
            Logger.printException(() -> "checkRestore failure", ex);
        }
    }

    private static void attemptRestoreSeek(long generation, String videoId,
                                           long targetPositionMs, int attempt) {
        if (generation != newVideoGeneration) {
            return;
        }

        final boolean seekAccepted = VideoInformation.seekTo(targetPositionMs);
        Logger.printDebug(() -> "Restore attempt: " + attempt + "/" + RESTORE_SEEK_MAX_ATTEMPTS
                + " target: " + targetPositionMs + " accepted: " + seekAccepted);

        Utils.runOnMainThreadDelayed(() -> {
            if (generation != newVideoGeneration) {
                return;
            }

            final long currentTime = VideoInformation.getVideoTime();
            if (Math.abs(currentTime - targetPositionMs) <= RESTORE_SEEK_TOLERANCE_MS) {
                Logger.printDebug(() -> "Restored livestream playback position: " + currentTime);
                restorePending = false;
                deletePlaybackPosition(videoId);
                return;
            }

            if (attempt < RESTORE_SEEK_MAX_ATTEMPTS) {
                // Player may not be ready to seek yet. Try again.
                attemptRestoreSeek(generation, videoId, targetPositionMs, attempt + 1);
                return;
            }

            Logger.printDebug(() -> "Restore gave up. Current time: " + currentTime);
            restorePending = false;
            deletePlaybackPosition(videoId);
        }, RESTORE_SEEK_RETRY_DELAY_MS);
    }

    private static void rescheduleOrGiveUp(long generation) {
        Utils.verifyOnMainThread();
        if (++restoreAttempts <= RESTORE_MAX_ATTEMPTS) {
            startRestoreCheck(generation);
            return;
        }

        Logger.printDebug(() -> "Restore polling gave up. videoId:"
                + VideoInformation.getVideoId() + " videoLength: " + VideoInformation.getVideoLength());
        restorePending = false;
    }

    private static synchronized Map<String, SavedPosition> getSavedPositions() {
        if (savedPositions == null) {
            savedPositions = loadSavedPositions();
        }
        return savedPositions;
    }

    private static Map<String, SavedPosition> loadSavedPositions() {
        String raw = Settings.REMEMBER_LIVESTREAM_POSITION_TIMES.get();
        if (raw.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            Map<String, SavedPosition> map = new HashMap<>(2 * json.length());
            while (keys.hasNext()) {
                String videoId = keys.next();
                JSONObject entry = json.optJSONObject(videoId);
                if (entry != null) {
                    final long position = entry.optLong("position", -1);
                    final long videoLength = entry.optLong("videoLength", -1);
                    final long timestamp = entry.optLong("timestamp", -1);
                    if (position >= 0 && videoLength >= 0) {
                        map.put(videoId, new SavedPosition(position, videoLength, timestamp));
                    }
                }
            }
            return map;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load livestream positions setting", ex);
            Settings.REMEMBER_LIVESTREAM_POSITION_TIMES.resetToDefault();
            return Collections.emptyMap();
        }
    }

    private static void saveSavedPositions() {
        if (savedPositions == null) {
            return;
        }

        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, SavedPosition> entry : savedPositions.entrySet()) {
                SavedPosition pos = entry.getValue();
                JSONObject entryJson = new JSONObject();
                entryJson.put("position", pos.position);
                entryJson.put("videoLength", pos.videoLength);
                entryJson.put("timestamp", pos.timestamp);
                json.put(entry.getKey(), entryJson);
            }
            Settings.REMEMBER_LIVESTREAM_POSITION_TIMES.save(json.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save livestream positions setting", ex);
        }
    }

    private static void savePlaybackPosition(String videoId, long positionMs, long videoLengthMs) {
        if (videoId.isEmpty()) {
            return;
        }

        Map<String, SavedPosition> map = getSavedPositions();
        map.put(videoId, new SavedPosition(positionMs, videoLengthMs, System.currentTimeMillis()));
        Logger.printDebug(() -> "Saved pos: " + positionMs
                + " len: " + videoLengthMs + " id: " + videoId);

        trimSavedPositions();
        saveSavedPositions();
    }

    private static SavedPosition loadPlaybackPosition(String videoId) {
        return getSavedPositions().get(videoId);
    }

    private static void deletePlaybackPosition(String videoId) {
        Map<String, SavedPosition> map = getSavedPositions();
        if (map.remove(videoId) != null) {
            saveSavedPositions();
        }
    }

    private static void trimSavedPositions() {
        Map<String, SavedPosition> map = getSavedPositions();

        while (map.size() > MAX_SAVED_STREAMS) {
            String oldestKey = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<String, SavedPosition> entry : map.entrySet()) {
                if (entry.getValue().timestamp < oldestTimestamp) {
                    oldestTimestamp = entry.getValue().timestamp;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                map.remove(oldestKey);
            } else {
                break;
            }
        }
    }
}
