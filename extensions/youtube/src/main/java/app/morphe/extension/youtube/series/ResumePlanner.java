package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import java.util.*;

/** Deterministic navigation. Catalog occurrences stay distinct; progress is shared by video. */
public final class ResumePlanner {
    public enum Kind {
        RESUME,
        NEXT,
        CHOOSE,
        CAUGHT_UP,
        NO_PLAYABLE,
        OPEN_PLAYLIST
    }

    public static final class Plan {
        public final Kind kind;
        public final String videoId, messageKey;
        public final int ordinal;
        public final long positionMs;

        Plan(Kind kind, String id, int ordinal, long position, String message) {
            this.kind = kind;
            videoId = id;
            this.ordinal = ordinal;
            positionMs = position;
            this.messageKey = message;
        }

        public boolean playable() {
            return kind == Kind.RESUME || kind == Kind.NEXT;
        }
    }

    private static Plan state(Kind kind, String message) {
        return new Plan(kind, "", -1, 0, message);
    }

    private static Plan play(Series s, Episode e, Kind kind) {
        Progress p = s.progress(e.videoId);
        long position = p.watched() ? 0 : p.positionMs;
        return new Plan(kind, e.videoId, e.ordinal, position, "series_tracker_episode_position");
    }

    public static Plan plan(Series s) {
        if (!s.complete()) {
            if (!s.bookmarkId.isEmpty()) {
                Progress p = s.progress(s.bookmarkId);
                return new Plan(
                        Kind.RESUME,
                        s.bookmarkId,
                        -1,
                        p.watched() ? 0 : p.positionMs,
                        "series_tracker_saved_position");
            }
            return state(Kind.OPEN_PLAYLIST, "series_tracker_plan_open");
        }
        if (!s.cursorId.isEmpty()) {
            Episode cursor = null;
            for (Episode e : s.episodes)
                if (e.ordinal == s.cursorOrdinal && e.videoId.equals(s.cursorId)) cursor = e;
            if (s.cursorRevision != s.revision || cursor == null || !cursor.available)
                return state(Kind.CHOOSE, "series_tracker_plan_changed");
            if (s.startHere || !s.progress(cursor.videoId).watched())
                return play(s, cursor, Kind.RESUME);
            boolean after = false;
            for (Episode e : s.episodes) {
                if (after && e.available && !s.progress(e.videoId).watched())
                    return play(s, e, Kind.NEXT);
                if (e.ordinal == cursor.ordinal) after = true;
            }
            return state(Kind.CAUGHT_UP, "series_tracker_plan_caught_up");
        }
        if (s.playableCount() == 0) return state(Kind.NO_PLAYABLE, "series_tracker_plan_none");
        Map<String, Integer> occurrences = new HashMap<>();
        for (Episode e : s.episodes) occurrences.merge(e.videoId, 1, Integer::sum);
        Episode recent = null;
        for (Episode e : s.episodes) {
            Progress p = s.progress(e.videoId);
            if (e.available
                    && !p.watched()
                    && p.playedAt > 0
                    && occurrences.get(e.videoId) == 1
                    && (recent == null || p.playedAt > s.progress(recent.videoId).playedAt))
                recent = e;
        }
        if (recent != null) return play(s, recent, Kind.RESUME);
        for (Episode e : s.episodes)
            if (e.available && !s.progress(e.videoId).watched()) return play(s, e, Kind.RESUME);
        return state(Kind.CAUGHT_UP, "series_tracker_plan_caught_up");
    }

    public static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return seconds >= 3600
                ? String.format(
                        Locale.ROOT,
                        "%d:%02d:%02d",
                        seconds / 3600,
                        seconds / 60 % 60,
                        seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private ResumePlanner() {}
}
