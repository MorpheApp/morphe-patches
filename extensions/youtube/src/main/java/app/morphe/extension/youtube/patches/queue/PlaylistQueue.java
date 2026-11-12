/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.VideoInformation;

/** Immutable playlist presentation; selection and repeat mirror the native playback queue. */
public final class PlaylistQueue {
    // YouTube's native repeat observer uses off=0, all=1, one=2.
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    /** Original source occurrence, independent of edits to the local queue order. */
    record SourcePosition(PlaylistPage.Anchor anchor, Object origin) {}

    public record Entry(String videoId, String title, @Nullable VideoMetadata metadata,
                        @Nullable SourcePosition sourcePosition) {
        public Entry(String videoId, String title, @Nullable VideoMetadata metadata) {
            this(videoId, title, metadata, null);
        }

        public Entry(String videoId, String title) {
            this(videoId, title, null);
        }
    }

    record Source(String playlistId, PlaylistPage.Account account, @Nullable PlaylistPage.Anchor anchor,
                  int boundary, Object origin) {
        Source(String playlistId, PlaylistPage.Account account) {
            this(playlistId, account, null, 0, new Object());
        }

        Source moveBoundary(int boundary) {
            return new Source(playlistId, account, anchor, boundary, origin);
        }
    }

    private final List<Entry> entries;
    private final int selectedIndex;
    private final int repeatMode;
    @Nullable
    private final Source source;
    private final int totalCount;
    @Nullable
    private final PlaylistQueueRenderer.Item unavailableVideosMessage;

    public PlaylistQueue(String playingVideoId, String playingTitle) {
        selectedIndex = 0;
        repeatMode = REPEAT_OFF;
        source = null;
        totalCount = 1;
        unavailableVideosMessage = null;
        requireVideoId(playingVideoId);
        entries = List.of(new Entry(playingVideoId,
                playingTitle == null || playingTitle.isEmpty() ? playingVideoId : playingTitle));
    }

    private PlaylistQueue(List<Entry> entries, int selectedIndex, int repeatMode,
                          @Nullable Source source, int totalCount, @Nullable PlaylistQueueRenderer.Item unavailableVideosMessage) {
        this.entries = List.copyOf(entries);
        this.selectedIndex = selectedIndex;
        this.repeatMode = repeatMode;
        this.source = source;
        this.totalCount = Math.max(entries.size(), totalCount);
        this.unavailableVideosMessage = unavailableVideosMessage;
    }

    /** A null source retains an already accepted finite Mix without generating more pages. */
    static PlaylistQueue fromPage(PlaylistPage page, @Nullable Source source) {
        List<Entry> entries = new ArrayList<>();
        for (var row : page.rows()) {
            entries.add(new Entry(row.entry().videoId(), row.entry().title(), row.metadata(),
                    source == null ? null : new SourcePosition(row.anchor(), source.origin())));
        }
        final boolean complete = source == null || page.rows().get(0).anchor().index() == 0 && page.totalCount() == entries.size();
        return new PlaylistQueue(entries, 0, REPEAT_OFF, complete ? null
                : new Source(source.playlistId(), source.account(), page.rows().get(page.rows().size() - 1).anchor(),
                        entries.size(), source.origin()),
                page.totalCount(), page.unavailableVideosMessage());
    }

    /** A Mix is a complete generated batch; preserve duplicate occurrences and native display data. */
    static PlaylistQueue fromMetadata(List<VideoMetadata> videos) {
        if (videos.isEmpty()) {
            throw new IllegalArgumentException("Playlist has no playable videos");
        }
        List<Entry> entries = new ArrayList<>();
        for (var video : videos) {
            requireVideoId(video.videoId());
            entries.add(new Entry(video.videoId(), video.title(), video));
        }
        return new PlaylistQueue(entries, 0, REPEAT_OFF, null, entries.size(), null);
    }

    PlaylistQueue appendPage(PlaylistPage page, boolean end) {
        if (source == null || source.anchor() == null) {
            throw new IllegalStateException("Queue has no pending playlist page");
        }
        int anchor = page.find(source.anchor());
        if (anchor < 0 || anchor + 1 == page.rows().size() && !end) {
            throw new IllegalArgumentException("Playlist page did not advance from its requested occurrence");
        }
        List<Entry> updated = new ArrayList<>(entries);
        int boundary = source.boundary();
        for (var row : page.rows().subList(anchor + 1, page.rows().size())) {
            updated.add(boundary++, new Entry(row.entry().videoId(), row.entry().title(), row.metadata(),
                    new SourcePosition(row.anchor(), source.origin())));
        }
        int selection = selectedIndex >= source.boundary() ? selectedIndex + boundary - source.boundary() : selectedIndex;
        return new PlaylistQueue(updated, selection, repeatMode, end ? null
                : new Source(source.playlistId(), source.account(), page.rows().get(page.rows().size() - 1).anchor(),
                        boundary, source.origin()),
                totalCount, page.unavailableVideosMessage() == null
                        ? unavailableVideosMessage : page.unavailableVideosMessage());
    }

    @Nullable
    Source source() {
        return source;
    }

    /** Only the unfinished source can extend its window during playback. */
    @Nullable
    PlaylistPage.Anchor playbackAnchor() {
        var position = currentEntry().sourcePosition();
        return source != null && position != null && position.origin() == source.origin() ? position.anchor() : null;
    }

    int totalCount() {
        return totalCount;
    }

    /** Hidden rows explain the retained total only after pagination has finished. */
    @Nullable
    PlaylistQueueRenderer.Item unavailableVideosMessage() {
        return source == null && totalCount > entries.size() ? unavailableVideosMessage : null;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public Entry currentEntry() {
        return entries.get(selectedIndex);
    }

    public int repeatMode() {
        return repeatMode;
    }

    public PlaylistQueue withRepeatMode(int mode) {
        if (mode < REPEAT_OFF || mode > REPEAT_ONE) {
            throw new IllegalArgumentException("Invalid repeat mode");
        }
        return mode == repeatMode ? this : new PlaylistQueue(entries, selectedIndex, mode, source, totalCount, unavailableVideosMessage);
    }

    public PlaylistQueue select(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        return new PlaylistQueue(entries, index, repeatMode, source, totalCount, unavailableVideosMessage);
    }

    public PlaylistQueue playNext(String videoId) {
        requireVideoId(videoId);
        var updated = new ArrayList<>(entries);
        updated.add(selectedIndex + 1, new Entry(videoId, videoId));
        Source following = source != null && selectedIndex + 1 <= source.boundary()
                ? source.moveBoundary(source.boundary() + 1) : source;
        return new PlaylistQueue(updated, selectedIndex, repeatMode, following, totalCount + 1, unavailableVideosMessage);
    }

    public PlaylistQueue playLast(String videoId) {
        requireVideoId(videoId);
        var updated = new ArrayList<>(entries);
        updated.add(new Entry(videoId, videoId));
        return new PlaylistQueue(updated, selectedIndex, repeatMode, source, totalCount + 1, unavailableVideosMessage);
    }

    /** Insert a fetched playlist without replacing current playback or its edited entries. */
    PlaylistQueue addPlaylist(PlaylistQueue incoming, boolean playLast) {
        if (incoming.source != null && (!playLast || source != null)) {
            throw new IllegalStateException("Only one unfinished playlist can own pagination");
        }
        final int position = playLast ? entries.size() : selectedIndex + 1;
        var updated = new ArrayList<>(entries);
        updated.addAll(position, incoming.entries);
        Source following = source;
        if (incoming.source != null) {
            following = incoming.source.moveBoundary(position + incoming.source.boundary());
        } else if (!playLast && source != null && position <= source.boundary()) {
            following = source.moveBoundary(source.boundary() + incoming.entries.size());
        }
        // One native footer covers the queue even when several source playlists hide videos.
        return new PlaylistQueue(updated, selectedIndex, repeatMode, following, totalCount + incoming.totalCount,
                incoming.unavailableVideosMessage == null ? unavailableVideosMessage : incoming.unavailableVideosMessage);
    }

    /** Insert after the playing entry when an outside video becomes the active watch video. */
    public PlaylistQueue playNextAndSelect(String videoId) {
        return playNext(videoId).select(selectedIndex + 1);
    }

    public PlaylistQueue remove(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        if (index == selectedIndex) {
            return this;
        }
        var updated = new ArrayList<>(entries);
        updated.remove(index);
        Source following = source != null && index < source.boundary()
                ? source.moveBoundary(source.boundary() - 1) : source;
        return new PlaylistQueue(updated, selectedIndex - (index < selectedIndex ? 1 : 0), repeatMode,
                following, totalCount - 1, unavailableVideosMessage);
    }

    /** Move an entry with the native adapter's remove-then-insert semantics. */
    public PlaylistQueue move(int from, int to) {
        if (from < 0 || from >= entries.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + from);
        }
        if (to < 0 || to >= entries.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + to);
        }
        if (from == to) {
            return this;
        }
        var updated = new ArrayList<>(entries);
        updated.add(to, updated.remove(from));

        // Entry identity also distinguishes duplicate videos in the same queue.
        int selection = selectedIndex;
        if (from == selection) {
            selection = to;
        } else if (from < selection && to >= selection) {
            selection--;
        } else if (from > selection && to <= selection) {
            selection++;
        }
        Source following = source;
        if (source != null) {
            int boundary = source.boundary() - (from < source.boundary() ? 1 : 0);
            following = source.moveBoundary(boundary + (to < source.boundary() ? 1 : 0));
        }
        return new PlaylistQueue(updated, selection, repeatMode, following, totalCount, unavailableVideosMessage);
    }

    /** Shuffle is a command: the displayed order also becomes the playback order. */
    public PlaylistQueue shuffle() {
        if (entries.size() < 2) {
            return this;
        }
        var updated = new ArrayList<>(entries);
        Entry playing = updated.remove(selectedIndex);

        // Use the same framework shuffler as YouTube's mutable playback queue.
        Collections.shuffle(updated);
        updated.add(0, playing);
        return new PlaylistQueue(updated, 0, repeatMode, source, totalCount, unavailableVideosMessage);
    }

    private static void requireVideoId(String videoId) {
        if (!VideoInformation.isVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
    }
}
