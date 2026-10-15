/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable read models shared by SQLite, planning and the UI. */
public final class TrackerModels {
    public enum Override {
        AUTO,
        WATCHED,
        UNWATCHED
    }

    public record Progress(
            String videoId,
            long positionMs,
            long durationMs,
            boolean autoCompleted,
            Override override,
            long playedAt,
            long editRevision) {
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
            this.videoInfo = videoInfo == null
                    ? ""
                    : videoInfo.substring(0, Math.min(240, videoInfo.length()));
            this.ordinal = ordinal;
            videoId = id == null ? "" : id;
            this.title = title == null ? "" : title;
            durationMs = duration;
            this.available = available && id != null && !id.isEmpty();
        }
    }

    public record Series(
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
            boolean hideWatched,
            // Video to continue with if the saved episode was removed from the playlist, or empty.
            String recoveryId) {
        public Series {
            List<Episode> ordered = new ArrayList<>(episodes);
            if (reverseOrder) Collections.reverse(ordered);
            episodes = Collections.unmodifiableList(ordered);
            // Map.copyOf is API 30 and crashes lint's API check inside a compact constructor.
            //noinspection Java9CollectionFactory
            progress = Collections.unmodifiableMap(new HashMap<>(progress));
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
