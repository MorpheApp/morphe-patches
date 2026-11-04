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
import java.util.regex.Pattern;

/** Immutable playlist presentation; selection and repeat mirror the native playback queue. */
public final class PlaylistQueue {
    // YouTube's native repeat observer uses off=0, all=1, one=2.
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public record Entry(String videoId, String title, @Nullable VideoMetadata metadata) {
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

    public PlaylistQueue(String playingVideoId, String playingTitle) {
        selectedIndex = 0;
        repeatMode = REPEAT_OFF;
        source = null;
        totalCount = 1;
        requireVideoId(playingVideoId);
        entries = List.of(new Entry(playingVideoId,
                playingTitle == null || playingTitle.isEmpty() ? playingVideoId : playingTitle));
    }

    private PlaylistQueue(List<Entry> entries, int selectedIndex, int repeatMode,
                          @Nullable Source source, int totalCount) {
        this.entries = List.copyOf(entries);
        this.selectedIndex = selectedIndex;
        this.repeatMode = repeatMode;
        this.source = source;
        this.totalCount = Math.max(entries.size(), totalCount);
    }

    static PlaylistQueue fromPage(PlaylistPage page, Source source) {
        List<Entry> entries = new ArrayList<>();
        for (var row : page.rows()) {
            entries.add(new Entry(row.entry().videoId(), row.entry().title(), row.metadata()));
        }
        boolean complete = page.rows().get(0).anchor().index() == 0 && page.totalCount() == entries.size();
        return new PlaylistQueue(entries, 0, REPEAT_OFF, complete ? null
                : new Source(source.playlistId(), source.account(), page.rows().get(page.rows().size() - 1).anchor(),
                        entries.size(), source.origin()),
                page.totalCount());
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
        return new PlaylistQueue(entries, 0, REPEAT_OFF, null, entries.size());
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
            updated.add(boundary++, new Entry(row.entry().videoId(), row.entry().title(), row.metadata()));
        }
        int selection = selectedIndex >= source.boundary() ? selectedIndex + boundary - source.boundary() : selectedIndex;
        return new PlaylistQueue(updated, selection, repeatMode, end ? null
                : new Source(source.playlistId(), source.account(), page.rows().get(page.rows().size() - 1).anchor(),
                        boundary, source.origin()),
                totalCount);
    }

    @Nullable
    Source source() {
        return source;
    }

    int totalCount() {
        return totalCount;
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
        return mode == repeatMode ? this : new PlaylistQueue(entries, selectedIndex, mode, source, totalCount);
    }

    public PlaylistQueue select(int index) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Index out of range: " + index);
        }
        return new PlaylistQueue(entries, index, repeatMode, source, totalCount);
    }

    public PlaylistQueue playNext(String videoId) {
        requireVideoId(videoId);
        var updated = new ArrayList<>(entries);
        updated.add(selectedIndex + 1, new Entry(videoId, videoId));
        Source following = source != null && selectedIndex + 1 <= source.boundary()
                ? source.moveBoundary(source.boundary() + 1) : source;
        return new PlaylistQueue(updated, selectedIndex, repeatMode, following, totalCount + 1);
    }

    public PlaylistQueue playLast(String videoId) {
        requireVideoId(videoId);
        var updated = new ArrayList<>(entries);
        updated.add(new Entry(videoId, videoId));
        return new PlaylistQueue(updated, selectedIndex, repeatMode, source, totalCount + 1);
    }

    /** Append a fetched Mix batch atomically, retaining duplicate occurrences and selection. */
    PlaylistQueue append(List<Entry> additions) {
        var updated = new ArrayList<>(entries);
        for (Entry entry : additions) {
            requireVideoId(entry.videoId());
            updated.add(entry);
        }

        return new PlaylistQueue(updated, selectedIndex, repeatMode, source, totalCount + additions.size());
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
                following, totalCount - 1);
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
        return new PlaylistQueue(updated, selection, repeatMode, following, totalCount);
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
        return new PlaylistQueue(updated, 0, repeatMode, source, totalCount);
    }

    public static boolean isVideoId(String videoId) {
        return videoId != null && VIDEO_ID.matcher(videoId).matches();
    }

    private static void requireVideoId(String videoId) {
        if (!isVideoId(videoId)) {
            throw new IllegalArgumentException("Invalid YouTube video ID");
        }
    }
}
