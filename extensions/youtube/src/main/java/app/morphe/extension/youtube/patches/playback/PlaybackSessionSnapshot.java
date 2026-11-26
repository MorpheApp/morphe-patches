/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.shared.patches.BaseVideoInformation;

/** The last eligible player session for one YouTube account on this device. */
public record PlaybackSessionSnapshot(String account, String videoId, long positionMilliseconds, Kind kind,
                                      String playlistId, boolean usesKeptQueue, @Nullable Queue queue) {
    private static final int VERSION = 1;
    private static final int MAX_QUEUE_ITEMS = 5000;

    public enum Kind {
        VIDEO, SHORT, LIVE
    }

    public record Queue(List<String> videoIds, int selectedIndex, @Nullable Integer nativeRepeatMode) {
        public Queue {
            videoIds = List.copyOf(videoIds);
            if (videoIds.size() > MAX_QUEUE_ITEMS || selectedIndex < 0 || selectedIndex >= videoIds.size()
                    || nativeRepeatMode != null && (nativeRepeatMode < 0 || nativeRepeatMode > 2)
                    || !videoIds.stream().allMatch(BaseVideoInformation::isVideoId)) {
                throw new IllegalArgumentException("Invalid playback queue");
            }
        }

        List<String> upcoming() {
            return videoIds.subList(selectedIndex, videoIds.size());
        }
    }

    public PlaybackSessionSnapshot {
        if (account.isEmpty() || !BaseVideoInformation.isVideoId(videoId) || positionMilliseconds < 0
                || usesKeptQueue && (!playlistId.isEmpty() || queue != null)
                || queue != null && !videoId.equals(queue.videoIds().get(queue.selectedIndex()))) {
            throw new IllegalArgumentException("Invalid playback session");
        }
        Objects.requireNonNull(kind);
        Objects.requireNonNull(playlistId);
    }

    static PlaybackSessionSnapshot fromJson(String json) throws JSONException {
        JSONObject data = new JSONObject(json);
        if (data.getInt("version") != VERSION) {
            throw new JSONException("Unsupported playback session version");
        }
        JSONObject savedQueue = data.optJSONObject("queue");
        Queue queue = null;
        if (savedQueue != null) {
            JSONArray ids = savedQueue.getJSONArray("videos");
            if (ids.length() > MAX_QUEUE_ITEMS) {
                throw new JSONException("Playback queue is too large");
            }
            List<String> videoIds = new ArrayList<>(ids.length());
            for (int index = 0; index < ids.length(); index++) {
                videoIds.add(ids.getString(index));
            }
            queue = new Queue(videoIds, savedQueue.getInt("selected"),
                    savedQueue.has("repeat") ? savedQueue.getInt("repeat") : null);
        }
        return new PlaybackSessionSnapshot(data.getString("account"), data.getString("video"),
                data.getLong("position"), Kind.valueOf(data.getString("kind")), data.getString("playlist"),
                data.optBoolean("keptQueue"), queue);
    }

    String toJson() throws JSONException {
        JSONObject data = new JSONObject()
                .put("version", VERSION)
                .put("account", account)
                .put("video", videoId)
                .put("position", positionMilliseconds)
                .put("kind", kind.name())
                .put("playlist", playlistId)
                .put("keptQueue", usesKeptQueue);
        if (queue != null) {
            data.put("queue", new JSONObject()
                    .put("videos", new JSONArray(queue.videoIds()))
                    .put("selected", queue.selectedIndex())
                    .put("repeat", queue.nativeRepeatMode()));
        }
        return data.toString();
    }
}
