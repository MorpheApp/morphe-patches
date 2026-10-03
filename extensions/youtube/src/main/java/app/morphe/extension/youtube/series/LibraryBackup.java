package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import org.json.*;

import java.util.*;

/** Portable, allowlisted user state. No consent, identities, native History baselines or tokens. */
final class LibraryBackup {
    static final int MAX_CHARS = 8 * 1024 * 1024;
    static final int MAX_SERIES = 500, MAX_EPISODES = 50_000;

    static final class Data {
        final List<Series> series;

        Data(List<Series> series) {
            this.series = Collections.unmodifiableList(series);
        }
    }

    static String encode(List<Series> library) {
        try {
            if (library.size() > MAX_SERIES) throw invalid();
            int episodeCount = 0;
            for (Series s : library) episodeCount += s.episodes.size();
            if (episodeCount > MAX_EPISODES) throw invalid();
            JSONObject document =
                    new JSONObject().put("format", "series-tracker").put("version", 1);
            JSONArray series = new JSONArray();
            JSONObject progress = new JSONObject();
            for (Series s : library) {
                JSONObject row =
                        new JSONObject()
                                .put("id", s.id)
                                .put("name", s.name)
                                .put("complete", s.complete())
                                .put("reverse", s.reverseOrder)
                                .put("hideWatched", s.hideWatched)
                                .put("cursor", s.cursorId)
                                .put("ordinal", s.cursorOrdinal)
                                .put("cursorValid", s.cursorRevision == s.revision)
                                .put("bookmark", s.bookmarkId)
                                .put("startHere", s.startHere)
                                .put("fetchedAt", s.fetchedAt)
                                .put("activity", s.activity)
                                .put("recovery", s.recoveryId);
                JSONArray episodes = new JSONArray();
                List<Episode> ordered = new ArrayList<>(s.episodes);
                ordered.sort(Comparator.comparingInt(e -> e.ordinal));
                for (Episode e : ordered)
                    episodes.put(
                            new JSONObject()
                                    .put("ordinal", e.ordinal)
                                    .put("id", e.videoId)
                                    .put("title", e.title)
                                    .put("info", e.videoInfo)
                                    .put("duration", e.durationMs)
                                    .put("available", e.available));
                row.put("episodes", episodes);
                series.put(row);
                for (Progress p : s.progress.values())
                    progress.put(
                            p.videoId,
                            new JSONObject()
                                    .put("position", p.positionMs)
                                    .put("duration", p.durationMs)
                                    .put("completed", p.autoCompleted)
                                    .put("override", p.override.name())
                                    .put("playedAt", p.playedAt));
            }
            String text = document.put("series", series).put("progress", progress).toString();
            if (text.length() > MAX_CHARS) throw invalid();
            return text;
        } catch (JSONException e) {
            throw invalid();
        }
    }

    static Data decode(String text) {
        if (text == null || text.length() > MAX_CHARS) throw invalid();
        try {
            JSONObject doc = new JSONObject(text);
            if (!"series-tracker".equals(doc.getString("format")) || doc.getInt("version") != 1)
                throw invalid();
            JSONArray rows = doc.getJSONArray("series");
            JSONObject positions = doc.getJSONObject("progress");
            if (rows.length() > MAX_SERIES || positions.length() > MAX_EPISODES) throw invalid();
            List<Series> series = new ArrayList<>();
            Set<String> playlistIds = new HashSet<>();
            int count = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                String id = string(r, "id", 128);
                if (!id.matches("[A-Za-z0-9_-]{2,128}") || !playlistIds.add(id)) throw invalid();
                String name = string(r, "name", 2048);
                if (name.trim().isEmpty()) throw invalid();
                String cursor = video(r, "cursor"), bookmark = video(r, "bookmark");
                JSONArray entries = r.getJSONArray("episodes");
                count += entries.length();
                if (count > MAX_EPISODES) throw invalid();
                List<Episode> episodes = new ArrayList<>();
                Set<String> references = new HashSet<>();
                if (!cursor.isEmpty()) references.add(cursor);
                if (!bookmark.isEmpty()) references.add(bookmark);
                int previous = 0;
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject e = entries.getJSONObject(j);
                    int ordinal = e.getInt("ordinal");
                    if (ordinal <= previous || ordinal > 1_000_000) throw invalid();
                    previous = ordinal;
                    String video = video(e, "id");
                    if (!video.isEmpty()) references.add(video);
                    boolean available = e.getBoolean("available");
                    if (available && video.isEmpty()) throw invalid();
                    episodes.add(
                            new Episode(
                                    ordinal,
                                    video,
                                    string(e, "title", 2048),
                                    duration(e, "duration"),
                                    available,
                                    string(e, "info", 240)));
                }
                Map<String, Progress> progress = new HashMap<>();
                for (String video : references) {
                    JSONObject p = positions.optJSONObject(video);
                    if (p == null) continue;
                    long duration = duration(p, "duration"), position = duration(p, "position");
                    if (duration > 0 && position > duration) throw invalid();
                    progress.put(
                            video,
                            new Progress(
                                    video,
                                    position,
                                    duration,
                                    p.getBoolean("completed"),
                                    TrackerModels.Override.valueOf(p.getString("override")),
                                    timestamp(p, "playedAt"),
                                    0));
                }
                boolean complete = r.getBoolean("complete");
                if (!complete && !episodes.isEmpty()) throw invalid();
                int ordinal = r.getInt("ordinal");
                if (ordinal < -1 || ordinal > 1_000_000) throw invalid();
                Series restored =
                        new Series(
                                id,
                                name,
                                "",
                                complete ? 1 : 0,
                                cursor,
                                ordinal,
                                r.getBoolean("cursorValid") ? (complete ? 1 : 0) : -1,
                                bookmark,
                                complete ? "complete" : "bookmark",
                                "",
                                timestamp(r, "fetchedAt"),
                                timestamp(r, "activity"),
                                episodes,
                                progress,
                                r.getBoolean("startHere"),
                                0,
                                r.getBoolean("reverse"),
                                r.getBoolean("hideWatched"),
                                video(r, "recovery"));
                series.add(restored);
            }
            return new Data(series);
        } catch (JSONException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static String string(JSONObject object, String key, int limit) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof String) || ((String) raw).length() > limit) throw invalid();
        return (String) raw;
    }

    private static String video(JSONObject object, String key) throws JSONException {
        String value = string(object, key, 11);
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9_-]{11}")) throw invalid();
        return value;
    }

    private static long duration(JSONObject object, String key) throws JSONException {
        long value = object.getLong(key);
        if (value < 0 || value > 604_800_000L) throw invalid();
        return value;
    }

    private static long timestamp(JSONObject object, String key) throws JSONException {
        long value = object.getLong(key);
        if (value < 0 || value > 32_503_680_000_000L) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("series_tracker_error_backup_invalid");
    }

    private LibraryBackup() {}
}
