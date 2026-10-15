/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.Episode;
import static app.morphe.extension.youtube.series.TrackerModels.Progress;
import static app.morphe.extension.youtube.series.TrackerModels.Series;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    public record Plan(Kind kind, String videoId, int ordinal, long positionMs, String messageKey) {
        public boolean playable() {
            return kind == Kind.RESUME || kind == Kind.NEXT;
        }
    }

    private static Plan state(Kind kind, String message) {
        return new Plan(kind, "", -1, 0, message);
    }

    private static Plan play(Series s, Episode e, Kind kind) {
        Progress p = s.progress(e.videoId);
        long position = p.watched() ? 0 : p.positionMs();
        return new Plan(kind, e.videoId, e.ordinal, position, "morphe_series_tracker_episode_position");
    }

    public static Plan plan(Series s) {
        if (!s.complete()) {
            if (!s.bookmarkId().isEmpty()) {
                Progress p = s.progress(s.bookmarkId());
                return new Plan(
                        Kind.RESUME,
                        s.bookmarkId(),
                        -1,
                        p.watched() ? 0 : p.positionMs(),
                        "morphe_series_tracker_saved_position");
            }
            return state(Kind.OPEN_PLAYLIST, "morphe_series_tracker_ui_playlist_bookmark");
        }
        if (!s.cursorId().isEmpty()) {
            Episode cursor = null;
            for (Episode e : s.episodes()) {
                if (e.ordinal == s.cursorOrdinal() && e.videoId.equals(s.cursorId())) cursor = e;
            }
            if (s.cursorRevision() != s.revision() || cursor == null || !cursor.available) {
                return state(Kind.CHOOSE, "morphe_series_tracker_plan_changed");
            }
            if (s.startHere() || !s.progress(cursor.videoId).watched()) {
                return play(s, cursor, Kind.RESUME);
            }
            boolean after = false;
            for (Episode e : s.episodes()) {
                if (after && e.available && !s.progress(e.videoId).watched()) {
                    return play(s, e, Kind.NEXT);
                }
                if (e.ordinal == cursor.ordinal) after = true;
            }
            return state(Kind.CAUGHT_UP, "morphe_series_tracker_plan_caught_up");
        }
        if (s.playableCount() == 0) return state(Kind.NO_PLAYABLE, "morphe_series_tracker_plan_none");
        Map<String, Integer> occurrences = new HashMap<>();
        for (Episode e : s.episodes()) occurrences.merge(e.videoId, 1, Integer::sum);
        Episode recent = null;
        for (Episode e : s.episodes()) {
            Progress p = s.progress(e.videoId);
            if (e.available
                    && !p.watched()
                    && p.playedAt() > 0
                    && Objects.equals(occurrences.get(e.videoId), 1)
                    && (recent == null || p.playedAt() > s.progress(recent.videoId).playedAt()))
                recent = e;
        }
        if (recent != null) return play(s, recent, Kind.RESUME);
        for (Episode e : s.episodes())
            if (e.available && !s.progress(e.videoId).watched()) return play(s, e, Kind.RESUME);
        return state(Kind.CAUGHT_UP, "morphe_series_tracker_plan_caught_up");
    }

    /**
     * @return The ordinal of the episode in the current playlist, or -1 if it moved or is ambiguous.
     */
    static int occurrence(Series before, String video, int ordinal, Series current) {
        if (!before.id().equals(current.id()) || !before.epoch().equals(current.epoch())) return -1;
        int found = -1, count = 0;
        for (Episode e : current.episodes()) {
            if (!e.available || !e.videoId.equals(video)) continue;
            if (before.revision() == current.revision() && e.ordinal == ordinal) return ordinal;
            found = e.ordinal;
            count++;
        }
        return before.revision() != current.revision() && count == 1 ? found : -1;
    }

    /**
     * @return The next or previous available episode, or null if there is none.
     */
    @Nullable
    static Episode adjacent(Series series, String videoId, int ordinal, boolean previous) {
        int step = previous ? -1 : 1;
        for (int i = 0; i < series.episodes().size(); i++) {
            Episode current = series.episodes().get(i);
            if (current.ordinal != ordinal || !current.videoId.equals(videoId)) continue;
            for (int j = i + step; j >= 0 && j < series.episodes().size(); j += step) {
                Episode candidate = series.episodes().get(j);
                if (candidate.available) return candidate;
            }
            return null;
        }
        return null;
    }

    public static String time(long ms) {
        final long seconds = Math.max(0, ms) / 1000;
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
