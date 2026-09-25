package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;

import java.util.*;

/** Synchronous SQLite boundary. Production calls are serialized on TrackerService's worker. */
public final class TrackerRepository extends SQLiteOpenHelper {
    public static final String DATABASE = "series_library.db";
    public static final int VERSION = 1;
    private static final String UNREFERENCED =
            "NOT EXISTS (SELECT 1 FROM catalog_entry e WHERE e.video_id=video_progress.video_id)"
                + " AND NOT EXISTS (SELECT 1 FROM series s WHERE"
                + " s.cursor_id=video_progress.video_id OR s.bookmark_id=video_progress.video_id)";

    public TrackerRepository(Context context) {
        this(context, DATABASE);
    }

    TrackerRepository(Context context, String name) {
        super(context, name, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @java.lang.Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @java.lang.Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL(
                "INSERT INTO meta VALUES ('history_epoch',?),('manual_revision','0')",
                new Object[] {UUID.randomUUID().toString()});
        db.execSQL(
                "CREATE TABLE video_progress (video_id TEXT PRIMARY KEY, position_ms INTEGER NOT"
                    + " NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, auto_completed"
                    + " INTEGER NOT NULL DEFAULT 0, completion_override TEXT NOT NULL DEFAULT"
                    + " 'AUTO', played_at INTEGER NOT NULL DEFAULT 0, edited_at INTEGER NOT NULL"
                    + " DEFAULT 0, edit_revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL(
                "CREATE TABLE series (playlist_id TEXT PRIMARY KEY, name TEXT NOT NULL, epoch TEXT"
                    + " NOT NULL, catalog_revision INTEGER NOT NULL DEFAULT 0, cursor_id TEXT NOT"
                    + " NULL DEFAULT '', cursor_ordinal INTEGER NOT NULL DEFAULT -1,"
                    + " cursor_revision INTEGER NOT NULL DEFAULT 0, bookmark_id TEXT NOT NULL"
                    + " DEFAULT '', activity INTEGER NOT NULL, fetched_at INTEGER NOT NULL DEFAULT"
                    + " 0, status TEXT NOT NULL DEFAULT 'bookmark', error TEXT NOT NULL DEFAULT '',"
                    + " request_token TEXT NOT NULL DEFAULT '')");
        db.execSQL(
                "CREATE TABLE catalog_entry (playlist_id TEXT NOT NULL REFERENCES"
                    + " series(playlist_id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, video_id"
                    + " TEXT NOT NULL, title TEXT NOT NULL, duration_ms INTEGER NOT NULL, available"
                    + " INTEGER NOT NULL, PRIMARY KEY(playlist_id,ordinal))");
        db.execSQL("CREATE INDEX catalog_video ON catalog_entry(video_id)");
        db.execSQL("CREATE INDEX recent_progress ON video_progress(played_at DESC)");
    }

    @java.lang.Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException(
                "Unsupported Series Tracker schema upgrade " + oldVersion + " → " + newVersion);
    }

    @java.lang.Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new SQLiteException(
                "This library was created by a newer Series Tracker. Install a compatible update;"
                        + " history was kept.");
    }

    public String historyEpoch() {
        return scalar("SELECT value FROM meta WHERE key='history_epoch'");
    }

    public void recoverInterruptedFetches() {
        getWritableDatabase()
                .execSQL(
                        "UPDATE series SET status='error',error='Refresh interrupted. Tap Refresh"
                                + " to retry.',request_token='' WHERE status='loading'");
    }

    private String scalar(String sql, String... args) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    private String meta(String key, String id) {
        return scalar("SELECT value FROM meta WHERE key=?", key + ":" + id);
    }

    private void meta(String key, String id, String value) {
        if (value.isEmpty())
            getWritableDatabase().delete("meta", "key=?", new String[] {key + ":" + id});
        else
            getWritableDatabase()
                    .execSQL(
                            "INSERT OR REPLACE INTO meta(key,value) VALUES (?,?)",
                            new Object[] {key + ":" + id, value});
    }

    private Set<String> newEpisodes(String id) {
        String value = meta("new_episodes", id);
        return value.isEmpty()
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(Arrays.asList(value.split(",")));
    }

    public long lastRefreshAttempt(String id) {
        String value = meta("refresh_attempt", id);
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }

    public long refreshRetryAt(String id) {
        String value = meta("refresh_retry", id);
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }

    public void refreshAttempt(String id, long now) {
        meta("refresh_attempt", id, Long.toString(now));
    }

    public void refreshRetry(String id, long time) {
        meta("refresh_retry", id, time == 0 ? "" : Long.toString(time));
    }

    public void acknowledgeEpisodes(String id) {
        meta("new_episodes", id, "");
    }

    private long nextEditRevision() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE meta SET value=CAST(value AS INTEGER)+1 WHERE key='manual_revision'");
        return Long.parseLong(scalar("SELECT value FROM meta WHERE key='manual_revision'"));
    }

    public Progress progress(String id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT"
                                    + " position_ms,duration_ms,auto_completed,completion_override,played_at,edit_revision"
                                    + " FROM video_progress WHERE video_id=?",
                                new String[] {id})) {
            return c.moveToFirst()
                    ? new Progress(
                            id,
                            c.getLong(0),
                            c.getLong(1),
                            c.getInt(2) != 0,
                            TrackerModels.Override.valueOf(c.getString(3)),
                            c.getLong(4),
                            c.getLong(5))
                    : Progress.empty(id);
        }
    }

    private void ensureProgress(String id) {
        ContentValues v = new ContentValues();
        v.put("video_id", id);
        getWritableDatabase()
                .insertWithOnConflict("video_progress", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public Set<String> trackedVideoIds() {
        Set<String> ids = new HashSet<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT video_id FROM catalog_entry WHERE available=1 UNION SELECT"
                                    + " bookmark_id FROM series WHERE bookmark_id<>'' UNION SELECT"
                                    + " cursor_id FROM series WHERE cursor_id<>''",
                                null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return Collections.unmodifiableSet(ids);
    }

    private boolean isTracked(String id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT 1 FROM catalog_entry WHERE video_id=? AND available=1 UNION"
                                    + " ALL SELECT 1 FROM series WHERE bookmark_id=? OR cursor_id=?"
                                    + " LIMIT 1",
                                new String[] {id, id, id})) {
            return c.moveToFirst();
        }
    }

    public boolean checkpoint(PlaybackReducer.Snapshot snapshot, String historyEpoch) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!historyEpoch().equals(historyEpoch)
                    || !isTracked(snapshot.videoId)
                    || progress(snapshot.videoId).playedAt > snapshot.playedAt) return false;
            ensureProgress(snapshot.videoId);
            // Update only playback columns; a delayed checkpoint cannot overwrite a manual
            // override.
            db.execSQL(
                    "UPDATE video_progress SET position_ms=?,duration_ms=CASE WHEN ?>0 THEN ? ELSE"
                            + " duration_ms"
                            + " END,auto_completed=MAX(auto_completed,?),played_at=MAX(played_at,?)"
                            + " WHERE video_id=?",
                    new Object[] {
                        snapshot.positionMs,
                        snapshot.durationMs,
                        snapshot.durationMs,
                        snapshot.completed ? 1 : 0,
                        snapshot.playedAt,
                        snapshot.videoId
                    });
            try (Cursor c =
                    db.rawQuery(
                            "SELECT"
                                + " s.playlist_id,MIN(e.ordinal),s.cursor_id,s.cursor_ordinal,s.cursor_revision,s.catalog_revision"
                                + " FROM series s JOIN catalog_entry e ON"
                                + " s.playlist_id=e.playlist_id WHERE e.video_id=? AND"
                                + " e.available=1 GROUP BY s.playlist_id HAVING COUNT(*)=1",
                            new String[] {snapshot.videoId})) {
                while (c.moveToNext()) {
                    String pending = meta("start_here", c.getString(0));
                    if (pending.equals(snapshot.videoId)) meta("start_here", c.getString(0), "");
                    else if (!pending.isEmpty()) continue;
                    if (c.getString(2).isEmpty()
                            || (c.getInt(3) > 0
                                    && c.getLong(4) == c.getLong(5)
                                    && c.getInt(1) > c.getInt(3))) {
                        db.execSQL(
                                "UPDATE series SET"
                                    + " cursor_id=?,cursor_ordinal=?,cursor_revision=catalog_revision"
                                    + " WHERE playlist_id=?",
                                new Object[] {snapshot.videoId, c.getInt(1), c.getString(0)});
                    }
                    db.execSQL(
                            "UPDATE series SET activity=MAX(activity,?) WHERE playlist_id=?",
                            new Object[] {snapshot.playedAt, c.getString(0)});
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    long manualRevision() {
        return Long.parseLong(scalar("SELECT value FROM meta WHERE key='manual_revision'"));
    }

    int mergeRemote(
            String expectedEpoch,
            long expectedRevision,
            List<Series> before,
            NativeProgressSync.Result result,
            long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int changed = 0;
        try {
            if (!historyEpoch().equals(expectedEpoch) || manualRevision() != expectedRevision)
                return 0;
            Set<String> accepted = new HashSet<>(), changedSnapshots = new HashSet<>();
            for (NativeHistoryPage.Row row : result.rows.values()) {
                if (!isTracked(row.id)) continue;
                Progress local = progress(row.id);
                long observedAt = result.observedAt.getOrDefault(row.id, 0L);
                if (observedAt <= 0
                        || observedAt > now + 120_000
                        || result.durations.getOrDefault(row.id, 0L) <= 0) continue;
                String[] stored = meta("native_seen", row.id).split("\\|", -1);
                HistoryMergePolicy.Baseline previous = null;
                if (stored.length == 3 && stored[0].equals(result.account)) {
                    try {
                        previous =
                                new HistoryMergePolicy.Baseline(
                                        stored[1], Long.parseLong(stored[2]));
                    } catch (NumberFormatException ignored) {
                        /* Treat damaged baseline as unknown. */
                    }
                }
                if (previous != null && observedAt <= previous.seenAt) continue;
                boolean changedSnapshot =
                        previous != null && !previous.signature.equals(row.signature());
                if (changedSnapshot) changedSnapshots.add(row.id);
                // Keep baselines across a local clear so an unchanged native response cannot
                // restore it.
                boolean clearedSeed = previous == null && !meta("native_clear", row.id).isEmpty();
                Progress merged =
                        clearedSeed
                                ? local
                                : HistoryMergePolicy.merge(
                                        local,
                                        row,
                                        previous,
                                        observedAt,
                                        result.durations.getOrDefault(row.id, 0L),
                                        result.percent,
                                        result.seconds);
                meta(
                        "native_seen",
                        row.id,
                        result.account + "|" + row.signature() + "|" + observedAt);
                meta("native_clear", row.id, "");
                if (merged == local) continue;
                ensureProgress(row.id);
                db.execSQL(
                        "UPDATE video_progress SET"
                            + " position_ms=?,duration_ms=?,auto_completed=?,played_at=? WHERE"
                            + " video_id=?",
                        new Object[] {
                            merged.positionMs,
                            merged.durationMs,
                            merged.autoCompleted ? 1 : 0,
                            merged.playedAt,
                            row.id
                        });
                accepted.add(row.id);
                changed++;
            }
            Map<String, Series> currentLibrary = new HashMap<>();
            for (Series current : library()) currentLibrary.put(current.id, current);
            for (Series previous : before) {
                Series current = currentLibrary.get(previous.id);
                if (current == null
                        || current.startHere
                        || current.revision != previous.revision
                        || !current.cursorId.equals(previous.cursorId)
                        || current.cursorOrdinal != previous.cursorOrdinal) continue;
                Episode latest = null;
                // Native History order is relative recency. Do not stamp every old row as "just
                // watched".
                for (String id : result.rows.keySet()) {
                    List<Episode> matches = new ArrayList<>();
                    for (Episode ep : current.episodes)
                        if (ep.available && ep.videoId.equals(id)) matches.add(ep);
                    if (!matches.isEmpty()) {
                        if (matches.size() == 1) latest = matches.get(0);
                        break;
                    }
                }
                if (latest == null || !accepted.contains(latest.videoId)) continue;
                long observedAt = result.observedAt.getOrDefault(latest.videoId, 0L);
                Progress cursor = previous.progress(previous.cursorId);
                if (cursor.playedAt >= observedAt) continue;
                if (!previous.cursorId.isEmpty()
                        && !result.rows.containsKey(previous.cursorId)
                        && !changedSnapshots.contains(latest.videoId)) {
                    int from = -1, to = -1;
                    for (int i = 0; i < current.episodes.size(); i++) {
                        if (current.episodes.get(i).videoId.equals(previous.cursorId)) from = i;
                        if (current.episodes.get(i).videoId.equals(latest.videoId)) to = i;
                    }
                    if (from >= 0 && to < from) continue;
                }
                db.execSQL(
                        "UPDATE series SET"
                            + " cursor_id=?,cursor_ordinal=?,cursor_revision=catalog_revision,activity=MAX(activity,?)"
                            + " WHERE playlist_id=?",
                        new Object[] {latest.videoId, latest.ordinal, observedAt, current.id});
            }
            db.setTransactionSuccessful();
            return changed;
        } finally {
            db.endTransaction();
        }
    }

    public List<Series> library() {
        List<String> ids = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT playlist_id FROM series ORDER BY activity DESC,playlist_id",
                                null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        List<Series> rows = new ArrayList<>();
        for (String id : ids) rows.add(series(id));
        return rows;
    }

    public Series series(String id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT"
                                    + " name,epoch,catalog_revision,cursor_id,cursor_ordinal,cursor_revision,bookmark_id,status,error,fetched_at,activity"
                                    + " FROM series WHERE playlist_id=?",
                                new String[] {id})) {
            if (!c.moveToFirst())
                throw new IllegalStateException("series_tracker_error_series_changed");
            List<Episode> episodes = new ArrayList<>();
            org.json.JSONObject videoInfo = EpisodeInfo.decode(meta("episode_info", id));
            Map<String, Progress> progress = new HashMap<>();
            try (Cursor e =
                    getReadableDatabase()
                            .rawQuery(
                                    "SELECT"
                                        + " e.ordinal,e.video_id,e.title,e.duration_ms,e.available,p.position_ms,p.duration_ms,p.auto_completed,p.completion_override,p.played_at,p.edit_revision"
                                        + " FROM catalog_entry e LEFT JOIN video_progress p ON"
                                        + " e.video_id=p.video_id WHERE e.playlist_id=? ORDER BY"
                                        + " e.ordinal",
                                    new String[] {id})) {
                while (e.moveToNext()) {
                    episodes.add(
                            new Episode(
                                    e.getInt(0),
                                    e.getString(1),
                                    e.getString(2),
                                    e.getLong(3),
                                    e.getInt(4) != 0,
                                    videoInfo.optString(e.getString(1))));
                    if (!e.isNull(8))
                        progress.put(
                                e.getString(1),
                                new Progress(
                                        e.getString(1),
                                        e.getLong(5),
                                        e.getLong(6),
                                        e.getInt(7) != 0,
                                        TrackerModels.Override.valueOf(e.getString(8)),
                                        e.getLong(9),
                                        e.getLong(10)));
                }
            }
            if (!c.getString(6).isEmpty()) progress.put(c.getString(6), progress(c.getString(6)));
            return new Series(
                    id,
                    c.getString(0),
                    c.getString(1),
                    c.getLong(2),
                    c.getString(3),
                    c.getInt(4),
                    c.getLong(5),
                    c.getString(6),
                    c.getString(7),
                    c.getString(8),
                    c.getLong(9),
                    c.getLong(10),
                    episodes,
                    progress,
                    !c.getString(3).isEmpty() && c.getString(3).equals(meta("start_here", id)),
                    (int)
                            newEpisodes(id).stream()
                                    .filter(
                                            video ->
                                                    !progress.getOrDefault(
                                                                    video, Progress.empty(video))
                                                            .watched())
                                    .count(),
                    "true".equals(meta("reverse_order", id)),
                    "true".equals(meta("hide_watched", id)));
        }
    }

    public void saveSeries(
            String id, String name, String bookmark, CatalogClient.Catalog catalog, long now) {
        saveSeries(id, name, bookmark, catalog, "", now);
    }

    public void saveSeries(
            String id,
            String name,
            String bookmark,
            CatalogClient.Catalog catalog,
            String startVideo,
            long now) {
        saveSeries(id, name, bookmark, catalog, new FollowStart(startVideo, -1, false, false), now);
    }

    void saveSeries(
            String id,
            String name,
            String bookmark,
            CatalogClient.Catalog catalog,
            FollowStart selection,
            long now) {
        String startVideo = selection.video;
        Episode start = null;
        if (!startVideo.isEmpty()) {
            if (catalog != null)
                for (Episode e : catalog.episodes) {
                    if (e.videoId.equals(startVideo) && e.available) {
                        start = e;
                        if (!selection.reverse) break;
                    }
                }
            if (start == null)
                throw new IllegalArgumentException("series_tracker_video_not_in_playlist");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("playlist_id", id);
            row.put("name", name.isEmpty() ? id : name);
            row.put("epoch", UUID.randomUUID().toString());
            row.put("bookmark_id", bookmark);
            row.put("activity", now);
            db.insertOrThrow("series", null, row);
            if (catalog != null) replaceCatalog(id, catalog, now);
            meta("reverse_order", id, selection.reverse ? "true" : "");
            if (start != null) {
                if (selection.watchedBefore) {
                    Set<String> previous =
                            FollowStart.previous(catalog.episodes, startVideo, selection.reverse);
                    if (!previous.isEmpty()) markIds(previous, TrackerModels.Override.WATCHED, now);
                }
                if (selection.positionMs >= 0) {
                    // Following from the player explicitly starts another watch of this video.
                    markIds(Collections.singleton(startVideo), TrackerModels.Override.AUTO, now);
                    db.execSQL(
                            "UPDATE video_progress SET"
                                + " position_ms=?,duration_ms=?,auto_completed=0,played_at=? WHERE"
                                + " video_id=?",
                            new Object[] {
                                start.durationMs > 0
                                        ? Math.min(selection.positionMs, start.durationMs)
                                        : selection.positionMs,
                                start.durationMs,
                                now,
                                startVideo
                            });
                }
                startHere(id, series(id).revision, start.ordinal, start.videoId, now);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static final class FetchTicket {
        public final String id, epoch, token;

        FetchTicket(String id, String epoch, String token) {
            this.id = id;
            this.epoch = epoch;
            this.token = token;
        }
    }

    public FetchTicket beginRefresh(String id) {
        Series series = series(id);
        String token = UUID.randomUUID().toString();
        getWritableDatabase()
                .execSQL(
                        "UPDATE series SET request_token=?,status='loading',error='' WHERE"
                                + " playlist_id=?",
                        new Object[] {token, id});
        return new FetchTicket(id, series.epoch, token);
    }

    private boolean current(FetchTicket ticket) {
        return ticket.token.equals(
                scalar(
                        "SELECT request_token FROM series WHERE playlist_id=? AND epoch=?",
                        ticket.id,
                        ticket.epoch));
    }

    public boolean publish(FetchTicket ticket, CatalogClient.Catalog catalog, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!current(ticket)) return false;
            replaceCatalog(ticket.id, catalog, now);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public void fetchFailed(FetchTicket ticket, String error) {
        getWritableDatabase()
                .execSQL(
                        "UPDATE series SET status='error',error=?,request_token='' WHERE"
                                + " playlist_id=? AND epoch=? AND request_token=?",
                        new Object[] {error, ticket.id, ticket.epoch, ticket.token});
    }

    private void replaceCatalog(String id, CatalogClient.Catalog catalog, long now) {
        SQLiteDatabase db = getWritableDatabase();
        Series old = series(id);
        Set<String> previous = new HashSet<>(),
                available = new LinkedHashSet<>(),
                unseen = newEpisodes(id);
        for (Episode e : old.episodes) if (e.available) previous.add(e.videoId);
        for (Episode e : catalog.episodes) if (e.available) available.add(e.videoId);
        if (old.complete())
            for (String video : available) if (!previous.contains(video)) unseen.add(video);
        unseen.retainAll(available);
        meta("new_episodes", id, String.join(",", unseen));
        meta("episode_info", id, EpisodeInfo.encode(catalog.episodes));
        db.delete("catalog_entry", "playlist_id=?", new String[] {id});
        for (Episode e : catalog.episodes) {
            ContentValues v = new ContentValues();
            v.put("playlist_id", id);
            v.put("ordinal", e.ordinal);
            v.put("video_id", e.videoId);
            v.put("title", e.title);
            v.put("duration_ms", e.durationMs);
            v.put("available", e.available ? 1 : 0);
            db.insertOrThrow("catalog_entry", null, v);
        }
        long revision = old.revision + 1;
        String cursor = old.cursorId.isEmpty() ? old.bookmarkId : old.cursorId;
        int ordinal = -1, matches = 0;
        if (!cursor.isEmpty())
            for (Episode e : catalog.episodes)
                if (e.videoId.equals(cursor)) {
                    ordinal = e.ordinal;
                    matches++;
                }
        if (matches != 1) ordinal = -1;
        db.execSQL(
                "UPDATE series SET"
                    + " catalog_revision=?,cursor_id=?,bookmark_id=?,cursor_ordinal=?,cursor_revision=?,fetched_at=?,status='complete',error='',request_token=''"
                    + " WHERE playlist_id=?",
                new Object[] {
                    revision,
                    cursor,
                    cursor.isEmpty() ? old.bookmarkId : cursor,
                    ordinal,
                    revision,
                    now,
                    id
                });
    }

    public void select(String id, long revision, int ordinal, String videoId, boolean restart) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Series s = series(id);
            if (s.revision != revision)
                throw new IllegalStateException("series_tracker_error_playlist_changed");
            if (ordinal > 0
                    && s.episodes.stream()
                            .noneMatch(
                                    e ->
                                            e.ordinal == ordinal
                                                    && e.videoId.equals(videoId)
                                                    && e.available))
                throw new IllegalStateException("series_tracker_error_episode_unavailable");
            db.execSQL(
                    "UPDATE series SET cursor_id=?,cursor_ordinal=?,cursor_revision=?,bookmark_id=?"
                            + " WHERE playlist_id=?",
                    new Object[] {videoId, ordinal, revision, videoId, id});
            meta("start_here", id, "");
            acknowledgeEpisodes(id);
            if (restart) {
                ensureProgress(videoId);
                db.execSQL(
                        "UPDATE video_progress SET position_ms=0 WHERE video_id=?",
                        new Object[] {videoId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Explicit continuation selection. No playback or watched-state mutation. */
    public void startHere(String id, long revision, int ordinal, String videoId, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (ordinal <= 0)
                throw new IllegalArgumentException("series_tracker_error_episode_unavailable");
            select(id, revision, ordinal, videoId, false);
            meta("start_here", id, videoId);
            db.execSQL("UPDATE series SET activity=? WHERE playlist_id=?", new Object[] {now, id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static final class Undo {
        public final Map<String, TrackerModels.Override> previous;
        public final long revision;
        final String historyEpoch;

        Undo(Map<String, TrackerModels.Override> previous, long revision, String epoch) {
            this.previous = previous;
            this.revision = revision;
            historyEpoch = epoch;
        }
    }

    public Undo mark(String id, TrackerModels.Override override, long now) {
        return markIds(Collections.singleton(id), override, now);
    }

    public Undo markThrough(String playlist, long revision, int ordinal, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Series series = series(playlist);
            if (!series.complete() || series.revision != revision)
                throw new IllegalStateException("series_tracker_error_playlist_changed");
            Set<String> ids = new LinkedHashSet<>();
            boolean found = false;
            for (Episode e : series.episodes) {
                if (e.available && !e.videoId.isEmpty()) ids.add(e.videoId);
                if (e.ordinal == ordinal) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalStateException("series_tracker_error_playlist_changed");
            Undo undo = markIds(ids, TrackerModels.Override.WATCHED, now);
            db.setTransactionSuccessful();
            return undo;
        } finally {
            db.endTransaction();
        }
    }

    private Undo markIds(Set<String> ids, TrackerModels.Override override, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long revision = nextEditRevision();
            Map<String, TrackerModels.Override> before = new LinkedHashMap<>();
            for (String id : ids) {
                if (id.isEmpty()) continue;
                before.put(id, progress(id).override);
                ensureProgress(id);
                if (override == TrackerModels.Override.WATCHED)
                    db.delete("meta", "key LIKE 'start_here:%' AND value=?", new String[] {id});
                db.execSQL(
                        "UPDATE video_progress SET"
                            + " completion_override=?,edit_revision=?,edited_at=? WHERE video_id=?",
                        new Object[] {override.name(), revision, now, id});
            }
            db.setTransactionSuccessful();
            return new Undo(before, revision, historyEpoch());
        } finally {
            db.endTransaction();
        }
    }

    public void undo(Undo undo, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!undo.historyEpoch.equals(historyEpoch())) return;
            long revision = nextEditRevision();
            for (Map.Entry<String, TrackerModels.Override> entry : undo.previous.entrySet())
                db.execSQL(
                        "UPDATE video_progress SET"
                            + " completion_override=?,edit_revision=?,edited_at=? WHERE video_id=?"
                            + " AND edit_revision=?",
                        new Object[] {
                            entry.getValue().name(), revision, now, entry.getKey(), undo.revision
                        });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean hasEpisodeInfo(String id) {
        return !meta("episode_info", id).isEmpty();
    }

    public void seriesOptions(String id, long revision, boolean reverse, boolean hideWatched) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Series old = series(id);
            if (old.revision != revision)
                throw new IllegalStateException("series_tracker_error_playlist_changed");
            meta("reverse_order", id, reverse ? "true" : "");
            meta("hide_watched", id, hideWatched ? "true" : "");
            if (old.reverseOrder != reverse && old.complete())
                db.execSQL(
                        "UPDATE series SET catalog_revision=catalog_revision+1,cursor_revision=CASE"
                            + " WHEN cursor_revision=catalog_revision THEN catalog_revision+1 ELSE"
                            + " cursor_revision END WHERE playlist_id=?",
                        new Object[] {id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void rename(String id, String name) {
        if (name.trim().isEmpty())
            throw new IllegalArgumentException("series_tracker_error_name_required");
        getWritableDatabase()
                .execSQL(
                        "UPDATE series SET name=? WHERE playlist_id=?",
                        new Object[] {name.trim(), id});
    }

    public void remove(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("series", "playlist_id=?", new String[] {id});
            meta("start_here", id, "");
            meta("reverse_order", id, "");
            meta("hide_watched", id, "");
            meta("episode_info", id, "");
            meta("new_episodes", id, "");
            meta("refresh_attempt", id, "");
            meta("refresh_retry", id, "");
            db.execSQL("DELETE FROM video_progress WHERE " + UNREFERENCED);
            pruneNativeBaselines();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void clearHistory(String newEpoch) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL(
                    "INSERT OR REPLACE INTO meta(key,value) SELECT 'native_clear:' || video_id, '1'"
                        + " FROM catalog_entry WHERE video_id<>''");
            db.execSQL(
                    "INSERT OR REPLACE INTO meta(key,value) SELECT 'native_clear:' || bookmark_id,"
                        + " '1' FROM series WHERE bookmark_id<>''");
            db.delete("video_progress", null, null);
            db.execSQL("DELETE FROM meta WHERE key LIKE 'start_here:%'");
            db.execSQL(
                    "UPDATE series SET"
                            + " cursor_id='',cursor_ordinal=-1,cursor_revision=0,bookmark_id=''");
            db.execSQL(
                    "UPDATE meta SET value=? WHERE key='history_epoch'", new Object[] {newEpoch});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void pruneNativeBaselines() {
        for (String prefix : new String[] {"native_seen:", "native_clear:"})
            getWritableDatabase()
                    .execSQL(
                            "DELETE FROM meta WHERE key LIKE ? AND NOT EXISTS (SELECT 1 FROM"
                                + " catalog_entry WHERE video_id=substr(meta.key,?)) AND NOT EXISTS"
                                + " (SELECT 1 FROM series WHERE cursor_id=substr(meta.key,?) OR"
                                + " bookmark_id=substr(meta.key,?))",
                            new Object[] {
                                prefix + "%",
                                prefix.length() + 1,
                                prefix.length() + 1,
                                prefix.length() + 1
                            });
    }

    public void prune(long now) {
        getWritableDatabase().execSQL("DELETE FROM video_progress WHERE " + UNREFERENCED);
        pruneNativeBaselines();
    }
}
