/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.VideoInformation;

/** An authenticated native watch window; advertised totals can include hidden videos. */
public record PlaylistPage(List<Row> rows, int selectedIndex, int totalCount,
                           @Nullable PlaylistQueueRenderer.Item unavailableVideosMessage) {
    /** Stable account identity only. No credentials or native objects are retained. */
    public record Account(String id, boolean incognito) {}

    /** Full native command inputs, including the identity of a duplicate occurrence. */
    record Anchor(String playlistId, String videoId, int index, String entryId,
                  String params, ByteString tracking) {
        String key() {
            return entryId.isEmpty() ? "index:" + index + ":" + videoId : "entry:" + entryId;
        }
    }

    record Row(PlaylistQueue.Entry entry, VideoMetadata metadata, Anchor anchor) {}

    public PlaylistPage {
        rows = List.copyOf(rows);
    }

    /** Rejection details contain only a fixed reason and numeric row positions. */
    static final class InvalidPanel extends Exception {
        InvalidPanel(String reason, String detail) {
            super(reason + ": " + detail);
        }
    }

    /** Read-only windows may highlight an unavailable row; local playback remains authoritative. */
    static PlaylistPage readPanel(MessageLite source, String playlistId)
            throws InvalidProtocolBufferException, InvalidPanel {
        var panel = PlaylistQueueRenderer.Panel.parseFrom(source.toByteArray());
        // The panel ID is optional, as in complete native snapshots. Each
        // playable row must still name the expected source, and the next request requires
        // the exact requested occurrence before accepting any additional rows.
        if (!panel.getPlaylistId().isEmpty() && !playlistId.equals(panel.getPlaylistId())) {
            throw new InvalidPanel("source-other", "panel rows " + panel.getItemsCount());
        }
        List<Row> rows = new ArrayList<>();
        int selected = -1;
        int lastIndex = -1;
        boolean unavailableSelected = false;
        for (int index = 0; index < panel.getItemsCount(); index++) {
            var item = panel.getItems(index);
            if (!item.hasVideo()) {
                continue;
            }
            var video = item.getVideo();
            if (video.hasUnplayableText()) {
                // Native queue population skips these before reading a command.
                // Deleted/private placeholders can omit both video ID and endpoint.
                unavailableSelected |= video.getSelected();
                continue;
            }
            String position = "row " + (index + 1) + "/" + panel.getItemsCount();
            Anchor anchor = anchor(video.getNavigationEndpoint());
            if (anchor == null) {
                var endpoint = video.getNavigationEndpoint();
                String reason = "row-command";
                if (!endpoint.hasWatchEndpoint() && !endpoint.hasAlternateWatchEndpoint()) {
                    reason = "row-endpoint";
                } else if (endpoint.hasWatchEndpoint() ? !endpoint.getWatchEndpoint().hasIndex()
                        : !endpoint.getAlternateWatchEndpoint().hasIndex()) {
                    reason = "row-index";
                }
                throw new InvalidPanel(reason, position);
            }
            if (!playlistId.equals(anchor.playlistId())) {
                throw new InvalidPanel("row-source", position);
            }
            if (!video.getVideoId().equals(anchor.videoId())) {
                throw new InvalidPanel("row-video", position);
            }
            if (anchor.index() <= lastIndex) {
                throw new InvalidPanel("row-order", position + "; index " + anchor.index() + " <= " + lastIndex);
            }
            lastIndex = anchor.index();
            if (video.getSelected()) {
                if (selected >= 0) {
                    throw new InvalidPanel("row-selection", position);
                }
                selected = rows.size();
            }
            VideoMetadata metadata = VideoMetadata.fromPanelVideo(video);
            rows.add(new Row(new PlaylistQueue.Entry(metadata.videoId(), metadata.title()), metadata, anchor));
        }
        if (rows.isEmpty()) {
            throw new InvalidPanel("empty-page", "panel rows " + panel.getItemsCount());
        }
        // YouTube appends a messageRenderer after the playable rows when videos
        // are hidden. Keep the native row opaque, including its localized text.
        var lastItem = panel.getItems(panel.getItemsCount() - 1);
        var unavailableVideosMessage = !lastItem.hasVideo() && lastItem.hasMessageRenderer() ? lastItem : null;
        // This response cannot prove the current playable selection or EOF, but
        // its independently validated source rows may still extend a loaded gap.
        return new PlaylistPage(rows, unavailableSelected ? -1 : selected, panel.getVideoCount(), unavailableVideosMessage);
    }

    @Nullable
    static Anchor anchor(PlaylistQueueRenderer.NavigationEndpoint endpoint) {
        String list;
        String video;
        String entry = "";
        String params = "";
        int index;
        if (endpoint.hasWatchEndpoint()) {
            var watch = endpoint.getWatchEndpoint();
            if (!watch.hasIndex()) {
                return null;
            }
            list = watch.getPlaylistId();
            video = watch.getVideoId();
            index = watch.getIndex();
            entry = watch.getPlaylistSetVideoId();
            params = watch.getParams();
        } else if (endpoint.hasAlternateWatchEndpoint()) {
            var watch = endpoint.getAlternateWatchEndpoint();
            if (!watch.hasIndex()) {
                return null;
            }
            list = watch.getPlaylistId();
            video = watch.getVideoId();
            index = watch.getIndex();
        } else {
            return null;
        }
        return list.isEmpty() || !VideoInformation.isVideoId(video) || index < 0 ? null
                : new Anchor(list, video, index, entry, params, endpoint.getClickTrackingParams());
    }

    /** Reject missing/ambiguous or shifted anchors rather than stitching another window. */
    int find(Anchor anchor) {
        int result = -1;
        for (int index = 0; index < rows.size(); index++) {
            Anchor candidate = rows.get(index).anchor();
            if (candidate.key().equals(anchor.key())) {
                if (result >= 0 || !candidate.playlistId().equals(anchor.playlistId())
                        || !candidate.videoId().equals(anchor.videoId()) || candidate.index() != anchor.index()) {
                    return -1;
                }
                result = index;
            }
        }
        return result;
    }

    /** Null means an unrecognized native navigation variant, not end of playlist. */
    @Nullable
    static PlaylistQueueRenderer.NavigationEndpoint nextTarget(PlaylistQueueRenderer.PlaylistNavigation navigation) {
        if (navigation.hasNext()) {
            return navigation.getNext();
        }
        var target = navigation.getNextTarget();
        if (target.hasWatch()) {
            return target.getWatch().getCommand();
        }
        if (target.hasAutoplay()) {
            return target.getAutoplay().getCommand();
        }
        if (target.hasContinuation()) {
            return target.getContinuation().getCommand();
        }
        return target.toByteArray().length == 0
                ? PlaylistQueueRenderer.NavigationEndpoint.getDefaultInstance() : null;
    }

    static boolean endsSource(PlaylistQueueRenderer.NavigationEndpoint target, String playlistId) {
        if (target.hasWatchEndpoint()) {
            return !playlistId.equals(target.getWatchEndpoint().getPlaylistId());
        }
        if (target.hasAlternateWatchEndpoint()) {
            return !playlistId.equals(target.getAlternateWatchEndpoint().getPlaylistId());
        }
        if (target.hasWatchPlaylistEndpoint()) {
            return !playlistId.equals(target.getWatchPlaylistEndpoint().getPlaylistId());
        }
        if (target.toByteArray().length == 0) {
            return true;
        }
        throw new IllegalArgumentException("Unrecognized source navigation command");
    }
}
