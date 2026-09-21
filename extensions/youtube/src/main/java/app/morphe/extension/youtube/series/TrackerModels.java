package app.morphe.extension.youtube.series;

import java.util.*;

/** Immutable read models shared by SQLite, planning and the UI. */
public final class TrackerModels {
    public enum Override {
        AUTO,
        WATCHED,
        UNWATCHED
    }

    public static final class Progress {
        public final String videoId;
        public final long positionMs, durationMs, playedAt, editRevision;
        public final boolean autoCompleted;
        public final Override override;

        public Progress(
                String id,
                long position,
                long duration,
                boolean completed,
                Override override,
                long playedAt,
                long revision) {
            videoId = id;
            positionMs = position;
            durationMs = duration;
            autoCompleted = completed;
            this.override = override;
            this.playedAt = playedAt;
            editRevision = revision;
        }

        public boolean watched() {
            return override == Override.WATCHED || (override == Override.AUTO && autoCompleted);
        }

        public static Progress empty(String id) {
            return new Progress(id, 0, 0, false, Override.AUTO, 0, 0);
        }
    }

    public static final class Episode {
        public final int ordinal;
        public final String videoId, title, videoInfo;
        public final long durationMs;
        public final boolean available;

        public Episode(int ordinal, String id, String title, long duration, boolean available) {
            this(ordinal, id, title, duration, available, "");
        }

        public Episode(
                int ordinal,
                String id,
                String title,
                long duration,
                boolean available,
                String videoInfo) {
            this.videoInfo =
                    videoInfo == null
                            ? ""
                            : videoInfo.substring(0, Math.min(240, videoInfo.length()));
            this.ordinal = ordinal;
            videoId = id == null ? "" : id;
            this.title = title == null ? "" : title;
            durationMs = duration;
            this.available = available && id != null && !id.isEmpty();
        }
    }

    public static final class Series {
        public final String id, name, epoch, cursorId, bookmarkId, status, error;
        public final long revision, cursorRevision, fetchedAt, activity;
        public final int cursorOrdinal, newEpisodeCount;
        public final boolean startHere, reverseOrder, hideWatched;
        public final List<Episode> episodes;
        public final Map<String, Progress> progress;

        public Series(
                String id,
                String name,
                String epoch,
                long revision,
                String cursorId,
                int cursorOrdinal,
                long cursorRevision,
                String bookmarkId,
                String status,
                String error,
                long fetchedAt,
                long activity,
                List<Episode> episodes,
                Map<String, Progress> progress) {
            this(
                    id,
                    name,
                    epoch,
                    revision,
                    cursorId,
                    cursorOrdinal,
                    cursorRevision,
                    bookmarkId,
                    status,
                    error,
                    fetchedAt,
                    activity,
                    episodes,
                    progress,
                    false,
                    0);
        }

        public Series(
                String id,
                String name,
                String epoch,
                long revision,
                String cursorId,
                int cursorOrdinal,
                long cursorRevision,
                String bookmarkId,
                String status,
                String error,
                long fetchedAt,
                long activity,
                List<Episode> episodes,
                Map<String, Progress> progress,
                boolean startHere,
                int newEpisodeCount) {
            this(
                    id,
                    name,
                    epoch,
                    revision,
                    cursorId,
                    cursorOrdinal,
                    cursorRevision,
                    bookmarkId,
                    status,
                    error,
                    fetchedAt,
                    activity,
                    episodes,
                    progress,
                    startHere,
                    newEpisodeCount,
                    false,
                    false);
        }

        public Series(
                String id,
                String name,
                String epoch,
                long revision,
                String cursorId,
                int cursorOrdinal,
                long cursorRevision,
                String bookmarkId,
                String status,
                String error,
                long fetchedAt,
                long activity,
                List<Episode> episodes,
                Map<String, Progress> progress,
                boolean startHere,
                int newEpisodeCount,
                boolean reverseOrder,
                boolean hideWatched) {
            this.reverseOrder = reverseOrder;
            this.hideWatched = hideWatched;
            this.startHere = startHere;
            this.newEpisodeCount = newEpisodeCount;
            this.id = id;
            this.name = name;
            this.epoch = epoch;
            this.revision = revision;
            this.cursorId = cursorId;
            this.cursorOrdinal = cursorOrdinal;
            this.cursorRevision = cursorRevision;
            this.bookmarkId = bookmarkId;
            this.status = status;
            this.error = error;
            this.fetchedAt = fetchedAt;
            this.activity = activity;
            List<Episode> ordered = new ArrayList<>(episodes);
            if (reverseOrder) Collections.reverse(ordered);
            this.episodes = Collections.unmodifiableList(ordered);
            this.progress = Collections.unmodifiableMap(new HashMap<>(progress));
        }

        public List<Episode> visibleEpisodes() {
            List<Episode> visible = new ArrayList<>();
            for (Episode e : episodes)
                if (e.available && (!hideWatched || !progress(e.videoId).watched())) visible.add(e);
            return visible;
        }

        /** Display numbering follows playback order; source ordinals remain stable identities. */
        public int episodeNumber(int ordinal) {
            int number = 0;
            for (Episode e : episodes) {
                if (e.available) number++;
                if (e.ordinal == ordinal) return number;
            }
            return ordinal;
        }

        public boolean complete() {
            return revision > 0;
        }

        public Progress progress(String id) {
            return progress.getOrDefault(id, Progress.empty(id));
        }

        public int playableCount() {
            int count = 0;
            for (Episode e : episodes) if (e.available) count++;
            return count;
        }

        public int watchedCount() {
            int count = 0;
            for (Episode e : episodes) if (e.available && progress(e.videoId).watched()) count++;
            return count;
        }
    }

    private TrackerModels() {}
}
