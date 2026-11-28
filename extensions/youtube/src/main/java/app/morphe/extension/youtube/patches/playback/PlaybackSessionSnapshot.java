/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5436
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

import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.Panel;
import app.morphe.extension.shared.patches.BaseVideoInformation;

/** The last eligible player session for one YouTube account on this device. */
public record PlaybackSessionSnapshot(String account, String videoId, long positionMilliseconds, Kind kind,
                                      String playlistId, boolean usesKeptQueue, @Nullable Queue queue,
                                      @Nullable Boolean captionsEnabled) {
    private static final int VERSION = 1;
    private static final int MAX_QUEUE_ITEMS = 5000;

    public enum Kind {
        VIDEO, SHORT, LIVE
    }

    public record Queue(List<String> videoIds, int selectedIndex, @Nullable Integer nativeRepeatMode,
                        @Nullable Integer playlistIndex) {
        public Queue(List<String> videoIds, int selectedIndex, @Nullable Integer nativeRepeatMode) {
            this(videoIds, selectedIndex, nativeRepeatMode, null);
        }

        public Queue {
            videoIds = List.copyOf(videoIds);
            if (videoIds.size() > MAX_QUEUE_ITEMS || selectedIndex < 0 || selectedIndex >= videoIds.size()
                    || nativeRepeatMode != null && (nativeRepeatMode < 0 || nativeRepeatMode > 2)
                    || playlistIndex != null && playlistIndex < 0
                    || !videoIds.stream().allMatch(BaseVideoInformation::isVideoId)) {
                throw new IllegalArgumentException("Invalid playback queue");
            }
        }

        @Nullable
        static Queue fromPanel(Panel panel, String videoId) {
            List<String> videoIds = new ArrayList<>();
            int selected = -1;
            int local = -1;
            int byIndex = -1;
            int firstMatch = -1;
            int matches = 0;
            for (int index = 0; index < panel.getItemsCount(); index++) {
                var item = panel.getItems(index);
                if (!item.hasVideo()) continue;
                var video = item.getVideo();
                String id = video.getVideoId();
                boolean playable = !video.hasUnplayableText() && BaseVideoInformation.isVideoId(id);
                if (video.getSelected()) {
                    if (selected >= 0 || !playable || !videoId.equals(id)) return null;
                    selected = videoIds.size();
                }
                if (!playable) continue;
                if (videoId.equals(id)) {
                    if (matches++ == 0) firstMatch = videoIds.size();
                    if (panel.hasLocalSelectedIndex() && index == panel.getLocalSelectedIndex()) {
                        local = videoIds.size();
                    }
                    if (panel.hasSelectedIndex()) {
                        var endpoint = video.getNavigationEndpoint();
                        var watch = endpoint.getWatchEndpoint();
                        var alternate = endpoint.getAlternateWatchEndpoint();
                        if (watch.hasIndex() && watch.getIndex() == panel.getSelectedIndex()
                                || alternate.hasIndex() && alternate.getIndex() == panel.getSelectedIndex()) {
                            byIndex = byIndex == -1 ? videoIds.size() : -2;
                        }
                    }
                }
                videoIds.add(id);
            }
            if (selected < 0) selected = local;
            if (selected < 0) selected = byIndex;
            if (selected < 0 && matches == 1) selected = firstMatch;
            return selected < 0 ? null : new Queue(videoIds, selected, null,
                    panel.hasSelectedIndex() && panel.getSelectedIndex() >= 0 ? panel.getSelectedIndex() : null);
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

    public PlaybackSessionSnapshot(String account, String videoId, long positionMilliseconds, Kind kind,
                                   String playlistId, boolean usesKeptQueue, @Nullable Queue queue) {
        this(account, videoId, positionMilliseconds, kind, playlistId, usesKeptQueue, queue, null);
    }

    @Nullable
    Queue queueWhilePanelUnavailable(String currentPlaylistId, boolean reopenedSavedQueue) {
        boolean samePlaylist = currentPlaylistId.isEmpty()
                ? reopenedSavedQueue : currentPlaylistId.equals(playlistId);
        return samePlaylist ? queue : null;
    }

    @Nullable
    Queue queueForRestore() {
        // A saved playlist or Mix ID keeps the native panel and its continuation available.
        // A Morphe-managed local queue still uses its saved order even if a source ID is present.
        return queue != null && (playlistId.isEmpty() || queue.nativeRepeatMode() != null) ? queue : null;
    }

    boolean hadCaptionsEnabled() {
        return Boolean.TRUE.equals(captionsEnabled);
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
                    savedQueue.has("repeat") ? savedQueue.getInt("repeat") : null,
                    savedQueue.has("playlistIndex") ? savedQueue.getInt("playlistIndex") : null);
        }
        return new PlaybackSessionSnapshot(data.getString("account"), data.getString("video"),
                data.getLong("position"), Kind.valueOf(data.getString("kind")), data.getString("playlist"),
                data.optBoolean("keptQueue"), queue,
                data.opt("captions") instanceof Boolean enabled ? enabled : null);
    }

    String toJson() throws JSONException {
        JSONObject data = new JSONObject()
                .put("version", VERSION)
                .put("account", account)
                .put("video", videoId)
                .put("position", positionMilliseconds)
                .put("kind", kind.name())
                .put("playlist", playlistId)
                .put("keptQueue", usesKeptQueue)
                .put("captions", captionsEnabled);
        if (queue != null) {
            data.put("queue", new JSONObject()
                    .put("videos", new JSONArray(queue.videoIds()))
                    .put("selected", queue.selectedIndex())
                    .put("repeat", queue.nativeRepeatMode())
                    .put("playlistIndex", queue.playlistIndex()));
        }
        return data.toString();
    }
}
