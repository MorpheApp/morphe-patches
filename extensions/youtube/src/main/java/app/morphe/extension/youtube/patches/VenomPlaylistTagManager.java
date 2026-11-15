package app.morphe.extension.youtube.patches;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * WLPro-grade Smart Tagging Engine & Permanent SQLite Indexer for YouTube Playlists & Watch Later.
 */
@SuppressWarnings("unused")
public final class VenomPlaylistTagManager {

    private static volatile String activePlaylistId = "VLWL";
    private static volatile String activePlaylistTitle = "Watch Later";

    private static volatile VenomTagDatabase dbInstance;
    private static final ExecutorService FETCH_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Set<String> inFlightVideoIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, VideoTagRecord> memoryCache = new ConcurrentHashMap<>();

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(202[0-9]|201[0-9])\\b");

    public static synchronized VenomTagDatabase getDb(Context context) {
        if (dbInstance == null) {
            dbInstance = new VenomTagDatabase(context.getApplicationContext());
        }
        return dbInstance;
    }

    public static String normalizePlaylistId(@Nullable String rawId) {
        if (rawId == null || rawId.isEmpty()) return "";
        if (rawId.equals("WL") || rawId.equals("VLWL")) return "VLWL";
        if (rawId.startsWith("VLPL")) return rawId.substring(2);
        return rawId;
    }

    public static void setActivePlaylist(@Nullable String rawPlaylistId, @Nullable String title) {
        String norm = normalizePlaylistId(rawPlaylistId);
        if (!norm.isEmpty()) {
            activePlaylistId = norm;
            if (title != null && !title.trim().isEmpty()) {
                activePlaylistTitle = title.trim();
            } else if ("VLWL".equals(norm)) {
                activePlaylistTitle = "Watch Later";
            }
        }
    }

    public static String getActivePlaylistId() {
        return activePlaylistId;
    }

    /**
     * Called passively as playlist/Watch Later items render on screen.
     * Saves playlist membership immediately and fetches smart tags once per video.
     */
    public static void indexPlaylistItem(@Nullable String rawPlaylistId,
                                         String videoId,
                                         @Nullable String fallbackTitle,
                                         @Nullable String fallbackChannel) {
        if (videoId == null || videoId.length() != 11) return;
        Context ctx = Utils.getContext();
        if (ctx == null) return;

        String playlistId = normalizePlaylistId(rawPlaylistId);
        if (playlistId.isEmpty()) {
            playlistId = activePlaylistId;
        }

        VenomTagDatabase db = getDb(ctx);
        db.linkVideoToPlaylist(playlistId, videoId, fallbackTitle, fallbackChannel);

        if (!db.isVideoTagged(videoId)) {
            fetchAndClassifyVideoAsync(ctx, videoId, fallbackTitle, fallbackChannel);
        }
    }

    /**
     * Fetches official YouTube player metadata (microformat.category, title, author, shortDescription,
     * and filtered keywords) once per videoId and runs the Multi-Signal Smart Classifier.
     */
    public static void fetchAndClassifyVideoAsync(final Context context,
                                                  final String videoId,
                                                  @Nullable final String fallbackTitle,
                                                  @Nullable final String fallbackChannel) {
        if (videoId == null || videoId.length() != 11) return;
        final VenomTagDatabase db = getDb(context);
        if (db.isVideoTagged(videoId) || !inFlightVideoIds.add(videoId)) {
            return;
        }

        FETCH_EXECUTOR.submit(() -> {
            try {
                URL url = new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String payload = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\",\"clientVersion\":\"21.30.216\"}},\"videoId\":\"" + videoId + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) {
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONObject videoDetails = root.optJSONObject("videoDetails");
                if (videoDetails == null) return;

                String title = videoDetails.optString("title", fallbackTitle != null ? fallbackTitle : "");
                String author = videoDetails.optString("author", fallbackChannel != null ? fallbackChannel : "");
                String shortDesc = videoDetails.optString("shortDescription", "");
                if (shortDesc.length() > 500) {
                    shortDesc = shortDesc.substring(0, 500);
                }

                // Official YouTube category from microformat
                String ytCategory = "";
                JSONObject microformat = root.optJSONObject("microformat");
                if (microformat != null) {
                    JSONObject renderer = microformat.optJSONObject("playerMicroformatRenderer");
                    if (renderer != null) {
                        ytCategory = renderer.optString("category", "");
                    }
                }

                List<String> rawKeywords = new ArrayList<>();
                JSONArray kwArray = videoDetails.optJSONArray("keywords");
                if (kwArray != null) {
                    for (int i = 0; i < Math.min(kwArray.length(), 15); i++) {
                        String k = kwArray.optString(i, "").trim();
                        if (!k.isEmpty()) rawKeywords.add(k);
                    }
                }

                SmartTagResult result = SmartTagClassifier.classify(title, author, shortDesc, ytCategory, rawKeywords);
                db.saveTaggedVideo(videoId, title, author, result.primaryCategory, result.subBadge, result.yearTag, result.smartTagsCsv);
                memoryCache.put(videoId, new VideoTagRecord(
                        videoId, title, author, result.primaryCategory, result.subBadge, result.yearTag, result.smartTagsCsv, true
                ));
            } catch (Exception ex) {
                Logger.printDebug(() -> "Smart tag fetch error for " + videoId + ": " + ex.getMessage());
            } finally {
                inFlightVideoIds.remove(videoId);
            }
        });
    }

    // =========================================================================
    // Multi-Signal Smart Tag Classifier (Prevents "Pakistan vs Bangladesh -> How-to")
    // =========================================================================

    private static final class SmartTagResult {
        final String primaryCategory; // e.g. "Sports", "Tech", "Music", "Devotional", "How-to", "Cinema", "Gaming", "News"
        final String subBadge;        // e.g. "Sports · Cricket", "Tech · Android", "Devotional · Live"
        final String yearTag;         // e.g. "2026" or ""
        final String smartTagsCsv;    // e.g. "#Cricket  #ODI  #Highlights"

        SmartTagResult(String primaryCategory, String subBadge, String yearTag, String smartTagsCsv) {
            this.primaryCategory = primaryCategory;
            this.subBadge = subBadge;
            this.yearTag = yearTag;
            this.smartTagsCsv = smartTagsCsv;
        }
    }

    private static final class SmartTagClassifier {

        private static final Set<String> SPAM_KEYWORDS = new HashSet<>(Arrays.asList(
                "viral", "trending", "youtube", "video", "shorts", "subscribe", "share", "like",
                "new", "latest", "best", "top", "full", "hd", "4k", "official", "live"
        ));

        static SmartTagResult classify(String title,
                                       String channel,
                                       String description,
                                       String ytCategory,
                                       List<String> keywords) {
            String titleLow = title.toLowerCase(Locale.ROOT);
            String channelLow = channel.toLowerCase(Locale.ROOT);
            String combinedLow = titleLow + " " + channelLow + " " + description.toLowerCase(Locale.ROOT);
            String catLow = ytCategory.toLowerCase(Locale.ROOT);

            // 1. Extract Year Tag if present in title (e.g., 2026)
            String yearTag = "";
            Matcher ym = YEAR_PATTERN.matcher(title);
            if (ym.find()) {
                yearTag = ym.group(1);
            }

            // 2. High-confidence domain detection (Title + Channel + YouTube Official Category)
            String primary = "Other";
            String subBadge = "Other";
            LinkedHashSet<String> tags = new LinkedHashSet<>();

            if (containsAny(combinedLow, "cricket", "odi", "t20", "ipl", "test match", "wicket", "batting", "bowling", "icc", "bcci", "pcb", "asia cup", "world cup")
                    || (containsAny(titleLow, "beat", "vs", "v ", "highlights", "innings") && containsAny(combinedLow, "india", "pakistan", "australia", "england", "new zealand", "bangladesh", "south africa"))) {
                primary = "Sports";
                subBadge = "Sports · Cricket";
                tags.add("#Cricket");
                if (containsAny(combinedLow, "odi")) tags.add("#ODI");
                if (containsAny(combinedLow, "t20")) tags.add("#T20");
                if (containsAny(combinedLow, "ipl")) tags.add("#IPL");
                if (containsAny(combinedLow, "highlights")) tags.add("#Highlights");
            } else if (catLow.contains("sports") || containsAny(combinedLow, "football", "fifa", "premier league", "champions league", "nba", "ufc", "wwe", "f1", "formula 1", "goal", "match highlights")) {
                primary = "Sports";
                if (containsAny(combinedLow, "football", "fifa", "premier league", "goal")) {
                    subBadge = "Sports · Football";
                    tags.add("#Football");
                } else if (containsAny(combinedLow, "wwe", "ufc", "mma", "boxing")) {
                    subBadge = "Sports · Combat";
                    tags.add("#Wrestling");
                } else {
                    subBadge = "Sports";
                    tags.add("#Sports");
                }
            } else if (containsAny(combinedLow, "gurbani", "kirtan", "sgpc", "harmandir", "darbar sahib", "shabad", "path", "waheguru", "bhajan", "aarti", "mantra", "hanuman", "ramayana", "mahabharat")) {
                primary = "Devotional";
                subBadge = "Devotional";
                tags.add("#Gurbani");
                if (containsAny(combinedLow, "kirtan")) tags.add("#Kirtan");
                if (containsAny(titleLow, "live")) tags.add("#Live");
            } else if (catLow.contains("science") || catLow.contains("tech")
                    || containsAny(combinedLow, "android", "apk", "github", "kotlin", "javascript", "python", "ai ", "llm", "gemini", "openai", "deepseek", "termux", "browser", "extension", "smartphone", "processor", "gpu", "review", "unboxing")) {
                primary = "Tech";
                if (containsAny(combinedLow, "android", "apk", "kotlin", "termux", "mod")) {
                    subBadge = "Tech · Android";
                    tags.add("#Android");
                } else if (containsAny(combinedLow, "ai ", "llm", "gemini", "gpt", "deepseek")) {
                    subBadge = "Tech · AI";
                    tags.add("#AI");
                } else if (containsAny(combinedLow, "github", "code", "programming", "web", "extension")) {
                    subBadge = "Tech · Dev";
                    tags.add("#Coding");
                } else {
                    subBadge = "Tech";
                    tags.add("#Tech");
                }
            } else if (catLow.contains("gaming")
                    || containsAny(combinedLow, "clash of clans", "brawl stars", "boom beach", "fortnite", "minecraft", "gta", "valorant", "bgmi", "pubg", "warhammer", "gameplay", "walkthrough", "esports")) {
                primary = "Gaming";
                subBadge = "Gaming";
                tags.add("#Gaming");
                if (containsAny(combinedLow, "clash of clans")) tags.add("#ClashOfClans");
                if (containsAny(combinedLow, "warhammer")) tags.add("#Warhammer40K");
            } else if (catLow.contains("music")
                    || containsAny(combinedLow, "official video", "official audio", "lyrical", "lyrics", "song", "album", "remix", "bgm", "soundtrack", "ost", "theme song")) {
                primary = "Music";
                subBadge = "Music";
                tags.add("#Music");
                if (containsAny(combinedLow, "ost", "soundtrack", "theme", "bgm")) tags.add("#Soundtrack");
            } else if (catLow.contains("film") || catLow.contains("entertainment")
                    || containsAny(combinedLow, "trailer", "teaser", "movie", "cinema", "box office", "episode", "anime", "manga", "iruma", "dc ", "marvel", "the boys")) {
                primary = "Cinema";
                subBadge = containsAny(combinedLow, "anime", "manga") ? "Cinema · Anime" : "Cinema";
                tags.add(containsAny(combinedLow, "anime", "manga") ? "#Anime" : "#Movies");
                if (containsAny(combinedLow, "trailer", "teaser")) tags.add("#Trailer");
            } else if (catLow.contains("news")
                    || containsAny(combinedLow, "breaking news", "news", "geopolitics", "election", "parliament", "economy", "update")) {
                primary = "News";
                subBadge = "News";
                tags.add("#News");
            } else if (catLow.contains("howto") || catLow.contains("education")
                    || containsAny(titleLow, "how to ", "tutorial", "step by step", "guide", "course", "explained", "learn ")) {
                // Only classify as How-to when title explicitly indicates a guide OR YouTube category is Howto/Education
                primary = "How-to";
                subBadge = "How-to";
                tags.add("#Tutorial");
            } else if (!ytCategory.isEmpty()) {
                primary = ytCategory;
                subBadge = ytCategory;
                tags.add("#" + ytCategory.replaceAll("[^a-zA-Z0-9]", ""));
            }

            // 3. Add up to 4 clean, non-spam creator keywords that align with the video
            for (String kw : keywords) {
                if (tags.size() >= 6) break;
                String clean = kw.replaceAll("[^a-zA-Z0-9 ]", "").trim();
                String cleanLow = clean.toLowerCase(Locale.ROOT);
                if (clean.length() < 3 || clean.length() > 22 || SPAM_KEYWORDS.contains(cleanLow)) {
                    continue;
                }
                // Never add "how to" / "tutorial" tag to a Sports or News video
                if (("Sports".equals(primary) || "News".equals(primary))
                        && (cleanLow.contains("how to") || cleanLow.contains("tutorial"))) {
                    continue;
                }
                String camel = toHashtag(clean);
                if (camel.length() >= 3) {
                    tags.add(camel);
                }
            }

            StringBuilder csv = new StringBuilder();
            for (String t : tags) {
                if (csv.length() > 0) csv.append("  ");
                csv.append(t);
            }

            return new SmartTagResult(primary, subBadge, yearTag, csv.toString());
        }

        private static boolean containsAny(String text, String... needles) {
            for (String n : needles) {
                if (text.contains(n)) return true;
            }
            return false;
        }

        private static String toHashtag(String phrase) {
            String[] parts = phrase.split("\\s+");
            StringBuilder sb = new StringBuilder("#");
            for (String p : parts) {
                if (!p.isEmpty()) {
                    sb.append(Character.toUpperCase(p.charAt(0)));
                    if (p.length() > 1) sb.append(p.substring(1));
                }
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // Permanent SQLite Storage
    // =========================================================================

    public static final class VideoTagRecord {
        public final String videoId;
        public final String title;
        public final String channelName;
        public final String primaryCategory;
        public final String subBadge;
        public final String yearTag;
        public final String smartTagsCsv;
        public final boolean isTagged;

        public VideoTagRecord(String videoId,
                              String title,
                              String channelName,
                              String primaryCategory,
                              String subBadge,
                              String yearTag,
                              String smartTagsCsv,
                              boolean isTagged) {
            this.videoId = videoId;
            this.title = title != null ? title : "";
            this.channelName = channelName != null ? channelName : "";
            this.primaryCategory = primaryCategory != null && !primaryCategory.isEmpty() ? primaryCategory : "Other";
            this.subBadge = subBadge != null && !subBadge.isEmpty() ? subBadge : "Other";
            this.yearTag = yearTag != null ? yearTag : "";
            this.smartTagsCsv = smartTagsCsv != null ? smartTagsCsv : "";
            this.isTagged = isTagged;
        }
    }

    public static final class VenomTagDatabase extends SQLiteOpenHelper {
        private static final String DB_NAME = "venom_playlist_tags.db";
        private static final int DB_VERSION = 2;

        public VenomTagDatabase(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_videos (" +
                    "video_id TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "channel_name TEXT, " +
                    "primary_category TEXT, " +
                    "sub_badge TEXT, " +
                    "year_tag TEXT, " +
                    "smart_tags_csv TEXT, " +
                    "is_tagged INTEGER DEFAULT 0, " +
                    "updated_at INTEGER)");

            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_membership (" +
                    "playlist_id TEXT NOT NULL, " +
                    "video_id TEXT NOT NULL, " +
                    "added_at INTEGER, " +
                    "PRIMARY KEY (playlist_id, video_id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS playlist_videos");
            db.execSQL("DROP TABLE IF EXISTS playlist_membership");
            onCreate(db);
        }

        public synchronized void linkVideoToPlaylist(String playlistId,
                                                     String videoId,
                                                     @Nullable String fallbackTitle,
                                                     @Nullable String fallbackChannel) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues link = new ContentValues();
                link.put("playlist_id", playlistId);
                link.put("video_id", videoId);
                link.put("added_at", System.currentTimeMillis());
                db.insertWithOnConflict("playlist_membership", null, link, SQLiteDatabase.CONFLICT_IGNORE);

                ContentValues stub = new ContentValues();
                stub.put("video_id", videoId);
                if (fallbackTitle != null && !fallbackTitle.isEmpty()) stub.put("title", fallbackTitle);
                if (fallbackChannel != null && !fallbackChannel.isEmpty()) stub.put("channel_name", fallbackChannel);
                stub.put("is_tagged", 0);
                stub.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("playlist_videos", null, stub, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Exception ignored) {}
        }

        public synchronized boolean isVideoTagged(String videoId) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT is_tagged FROM playlist_videos WHERE video_id = ?",
                    new String[]{videoId})) {
                if (c.moveToFirst()) return c.getInt(0) == 1;
            } catch (Exception ignored) {}
            return false;
        }

        public synchronized void saveTaggedVideo(String videoId,
                                                 String title,
                                                 String channelName,
                                                 String primaryCategory,
                                                 String subBadge,
                                                 String yearTag,
                                                 String smartTagsCsv) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("video_id", videoId);
                cv.put("title", title);
                cv.put("channel_name", channelName);
                cv.put("primary_category", primaryCategory);
                cv.put("sub_badge", subBadge);
                cv.put("year_tag", yearTag);
                cv.put("smart_tags_csv", smartTagsCsv);
                cv.put("is_tagged", 1);
                cv.put("updated_at", System.currentTimeMillis());
                getWritableDatabase().insertWithOnConflict("playlist_videos", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignored) {}
        }

        public synchronized List<VideoTagRecord> queryPlaylist(
                @Nullable String playlistId,
                @Nullable String searchQuery,
                @Nullable String categoryFilter,
                @Nullable String channelFilter
        ) {
            List<VideoTagRecord> list = new ArrayList<>();
            String q = "%" + (searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT)) + "%";
            boolean byPlaylist = playlistId != null && !playlistId.isEmpty();
            boolean byCategory = categoryFilter != null && !categoryFilter.isEmpty();
            boolean byChannel = channelFilter != null && !channelFilter.isEmpty();

            StringBuilder sql = new StringBuilder();
            List<String> args = new ArrayList<>();

            if (byPlaylist) {
                sql.append("SELECT v.video_id, v.title, v.channel_name, v.primary_category, v.sub_badge, v.year_tag, v.smart_tags_csv, v.is_tagged ")
                   .append("FROM playlist_videos v ")
                   .append("INNER JOIN playlist_membership m ON v.video_id = m.video_id ")
                   .append("WHERE m.playlist_id = ? ");
                args.add(playlistId);
            } else {
                sql.append("SELECT v.video_id, v.title, v.channel_name, v.primary_category, v.sub_badge, v.year_tag, v.smart_tags_csv, v.is_tagged ")
                   .append("FROM playlist_videos v WHERE 1=1 ");
            }

            sql.append("AND (LOWER(COALESCE(v.title,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.channel_name,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.sub_badge,'')) LIKE ? ")
               .append("OR LOWER(COALESCE(v.smart_tags_csv,'')) LIKE ?) ");
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);

            if (byCategory) {
                sql.append("AND LOWER(COALESCE(v.primary_category,'')) = ? ");
                args.add(categoryFilter.toLowerCase(Locale.ROOT));
            }
            if (byChannel) {
                sql.append("AND LOWER(COALESCE(v.channel_name,'')) = ? ");
                args.add(channelFilter.toLowerCase(Locale.ROOT));
            }

            sql.append("ORDER BY v.updated_at DESC LIMIT 500");

            try (Cursor c = getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]))) {
                while (c.moveToNext()) {
                    list.add(new VideoTagRecord(
                            c.getString(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getString(4), c.getString(5),
                            c.getString(6), c.getInt(7) == 1
                    ));
                }
            } catch (Exception ignored) {}
            return list;
        }
    }

    // =========================================================================
    // WLPro UI Dialog (Matches Screenshot: Header Stats, Search, Category & Channel Chips)
    // =========================================================================

    public static void showPlaylistTagFilterDialog(final Context context, @Nullable final String playlistIdOverride) {
        final String targetPlaylistId = (playlistIdOverride != null && !playlistIdOverride.isEmpty())
                ? normalizePlaylistId(playlistIdOverride)
                : activePlaylistId;

        final VenomTagDatabase db = getDb(context);
        final List<VideoTagRecord> allItems = db.queryPlaylist(targetPlaylistId, "", "", "");

        int taggedCount = 0;
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        Map<String, Integer> channelCounts = new LinkedHashMap<>();

        for (VideoTagRecord rec : allItems) {
            if (rec.isTagged) taggedCount++;
            Integer catC = categoryCounts.get(rec.primaryCategory);
            categoryCounts.put(rec.primaryCategory, catC == null ? 1 : catC + 1);

            if (!rec.channelName.isEmpty()) {
                Integer chC = channelCounts.get(rec.channelName);
                channelCounts.put(rec.channelName, chC == null ? 1 : chC + 1);
            }
        }
        int percentTagged = allItems.isEmpty() ? 0 : Math.round((taggedCount * 100f) / allItems.size());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F0F0F"));
        int pad = dp(context, 14);
        root.setPadding(pad, pad, pad, pad);

        // 1. WLPro Status Header ("WLPro • X videos • Y% tagged")
        TextView statsHeader = new TextView(context);
        statsHeader.setText("WLPro  •  " + allItems.size() + " videos  •  " + percentTagged + "% tagged");
        statsHeader.setTextColor(Color.parseColor("#CCCCCC"));
        statsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        statsHeader.setPadding(0, 0, 0, dp(context, 10));
        root.addView(statsHeader);

        // 2. Search Input ("Search title, channel, tag...")
        final EditText searchBox = new EditText(context);
        searchBox.setHint("Search title, channel, tag...");
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(Color.parseColor("#888888"));
        searchBox.setSingleLine(true);
        searchBox.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.parseColor("#1C1C1C"));
        searchBg.setCornerRadius(dp(context, 12));
        searchBg.setStroke(dp(context, 1), Color.parseColor("#333333"));
        searchBox.setBackground(searchBg);
        root.addView(searchBox);

        // 3. Category & Channel Filter Chips Strip ("All", "Sports 2", "Tech 1", "Channel...")
        final String[] activeCategory = {""};
        final String[] activeChannel = {""};

        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(context, 10), 0, dp(context, 10));

        final List<VideoTagRecord> filteredItems = new ArrayList<>(allItems);
        final WLProVideoAdapter adapter = new WLProVideoAdapter(context, filteredItems);
        ListView listView = new ListView(context);
        listView.setAdapter(adapter);
        listView.setDividerHeight(dp(context, 1));

        final Runnable applyFilters = () -> {
            filteredItems.clear();
            filteredItems.addAll(db.queryPlaylist(
                    targetPlaylistId,
                    searchBox.getText().toString(),
                    activeCategory[0],
                    activeChannel[0]
            ));
            adapter.notifyDataSetChanged();
        };

        chipRow.addView(createPill(context, "All (" + allItems.size() + ")", true, v -> {
            activeCategory[0] = "";
            activeChannel[0] = "";
            applyFilters.run();
        }));

        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            final String cat = entry.getKey();
            chipRow.addView(createPill(context, cat + "  " + entry.getValue(), false, v -> {
                activeCategory[0] = cat.equals(activeCategory[0]) ? "" : cat;
                applyFilters.run();
            }));
        }

        for (Map.Entry<String, Integer> entry : channelCounts.entrySet()) {
            final String ch = entry.getKey();
            chipRow.addView(createPill(context, "👤 " + ch + "  " + entry.getValue(), false, v -> {
                activeChannel[0] = ch.equals(activeChannel[0]) ? "" : ch;
                applyFilters.run();
            }));
        }

        chipScroll.addView(chipRow);
        root.addView(chipScroll);

        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 380)));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(activePlaylistTitle)
                .setView(root)
                .setNegativeButton("Close", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredItems.size()) {
                VideoTagRecord selected = filteredItems.get(position);
                dialog.dismiss();
                openVideo(context, selected.videoId);
            }
        });

        dialog.show();
    }

    private static void openVideo(Context context, String videoId) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId));
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openVideo error", ex);
        }
    }

    private static final class WLProVideoAdapter extends BaseAdapter {
        private final Context context;
        private final List<VideoTagRecord> items;

        WLProVideoAdapter(Context context, List<VideoTagRecord> items) {
            this.context = context;
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            TextView badgeTv;
            TextView titleTv;
            TextView metaTv;

            if (convertView instanceof LinearLayout existing) {
                card = existing;
                badgeTv = (TextView) card.getChildAt(0);
                titleTv = (TextView) card.getChildAt(1);
                metaTv = (TextView) card.getChildAt(2);
            } else {
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10));

                badgeTv = new TextView(context);
                badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
                badgeTv.setTextColor(Color.parseColor("#81C784"));
                card.addView(badgeTv);

                titleTv = new TextView(context);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                titleTv.setTypeface(Typeface.DEFAULT_BOLD);
                titleTv.setTextColor(Color.WHITE);
                titleTv.setPadding(0, dp(context, 2), 0, dp(context, 2));
                card.addView(titleTv);

                metaTv = new TextView(context);
                metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                metaTv.setTextColor(Color.parseColor("#AAAAAA"));
                card.addView(metaTv);
            }

            VideoTagRecord item = items.get(position);
            String badgeLine = item.subBadge;
            if (!item.yearTag.isEmpty()) badgeLine += "   •   " + item.yearTag;
            if (!item.smartTagsCsv.isEmpty()) badgeLine += "   •   " + item.smartTagsCsv;
            badgeTv.setText(badgeLine);

            titleTv.setText(item.title.isEmpty() ? item.videoId : item.title);
            metaTv.setText(item.channelName.isEmpty() ? "Indexing channel..." : item.channelName);
            return card;
        }
    }

    private static View createPill(Context context, String text, boolean active, View.OnClickListener listener) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(active ? Color.BLACK : Color.WHITE);
        tv.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        tv.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 18));
        bg.setColor(active ? Color.parseColor("#E0E0E0") : Color.parseColor("#222222"));
        bg.setStroke(dp(context, 1), Color.parseColor("#444444"));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(context, 8));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(listener);
        return tv;
    }

    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }
}
