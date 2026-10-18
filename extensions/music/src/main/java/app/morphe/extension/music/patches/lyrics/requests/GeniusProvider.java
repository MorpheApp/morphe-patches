/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.shared.Logger;

/**
 * Genius song pages, read through the public endpoints its own web app uses, so no client token
 * is needed. The search answers JSON; the lyrics only exist in the page markup, which makes this
 * the one provider that has to read HTML.
 */
public final class GeniusProvider implements LyricsProvider {

    private static final String SEARCH = "https://genius.com/api/search/multi?q=";
    private static final String SONG_RECORD = "https://genius.com/api/songs/";
    /** Genius answers anything that is not a browser with HTTP 401. */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/140.0 Mobile Safari/537.36";

    private static final long RETRY_AFTER_CEILING_MS = 4000;

    /**
     * Genius answers a share of otherwise valid requests with 403 or a dropped connection, most
     * often when several arrive close together. Neither carries a Retry-After, so the waits are
     * fixed and short: long enough to clear the gap, short enough not to stall the lookup.
     */
    private static final long RETRY_403_WAIT_MS = 1000;

    private static final long RETRY_IO_WAIT_MS = 500;

    private static final int RECORD_READ_TIMEOUT_MS = 20_000;

    private static final int PAGE_READ_TIMEOUT_MS = 10_000;

    private static final long RECORD_JOIN_MS = RECORD_READ_TIMEOUT_MS + 2000;

    private static final long REQUEST_THROTTLE_MS = 300;

    private static final AtomicLong lastRequestTime = new AtomicLong();

    private static final int RECORD_CACHE_SIZE = 32;
    private static final Map<Long, JSONObject> recordCache =
            Collections.synchronizedMap(new LinkedHashMap<>(RECORD_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, JSONObject> eldest) {
                    return size() > RECORD_CACHE_SIZE;
                }
            });

    private static final ConcurrentHashMap<Long, Object> recordLocks = new ConcurrentHashMap<>();

    private static final Set<String> SONG_SECTIONS = Set.of("top_hit", "song");

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9]+)([^>]*)>");
    private static final Pattern CONTAINER =
            Pattern.compile("<div[^>]*data-lyrics-container=[\"']true[\"'][^>]*>",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");

    /** Tags whose open and close pairs change the nesting that delimits the lyric text. */
    private static final Set<String> NESTING_TAGS = Set.of(
            "div", "span", "a", "i", "b", "em", "strong", "p", "button",
            "ul", "li", "svg", "path", "label", "section");

    private static final Pattern COVER =
            Pattern.compile("\\b(version|cover)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern TRANSLATION =
            Pattern.compile("translat", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(
            Map.entry("english", "en"),
            Map.entry("chinese", "zh"),
            Map.entry("mandarin", "zh"),
            Map.entry("cantonese", "yue"),
            Map.entry("japanese", "ja"),
            Map.entry("korean", "ko"),
            Map.entry("spanish", "es"),
            Map.entry("french", "fr"),
            Map.entry("german", "de"),
            Map.entry("italian", "it"),
            Map.entry("portuguese", "pt"),
            Map.entry("russian", "ru"),
            Map.entry("dutch", "nl"),
            Map.entry("polish", "pl"),
            Map.entry("turkish", "tr"),
            Map.entry("arabic", "ar"),
            Map.entry("hebrew", "he"),
            Map.entry("hindi", "hi"),
            Map.entry("indonesian", "id"),
            Map.entry("vietnamese", "vi"),
            Map.entry("thai", "th"),
            Map.entry("swedish", "sv"),
            Map.entry("norwegian", "no"),
            Map.entry("danish", "da"),
            Map.entry("finnish", "fi"),
            Map.entry("greek", "el"),
            Map.entry("hungarian", "hu"),
            Map.entry("czech", "cs"),
            Map.entry("romanian", "ro"),
            Map.entry("ukrainian", "uk"),
            Map.entry("persian", "fa"),
            Map.entry("brazilian portuguese", "pt"),
            Map.entry("simplified chinese", "zh"),
            Map.entry("traditional chinese", "zh"));

    private static String languageCode(String name) {
        String trimmed = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        String mapped = LANGUAGE_NAMES.get(trimmed);
        return mapped != null ? mapped : trimmed;
    }

    @Override
    public String name() {
        return "Genius";
    }

    /** One search, one page, no candidate fan-out: only the first accepted hit is read. */
    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        JSONObject hit = findBestHit(track);
        if (hit == null) {
            return null;
        }
        String title = hit.optString("title", "");
        String artist = primaryArtistName(hit);
        String url = hit.optString("url", "");
        if (url.isEmpty()) {
            return null;
        }

        long songId = hit.optLong("id", 0L);
        JSONObject[] holder = new JSONObject[1];
        Thread recordThread = new Thread(() -> holder[0] = songRecord(songId),
                "Genius song record");
        recordThread.start();

        String html = fetchPage(url);
        try {
            recordThread.join(RECORD_JOIN_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        JSONObject record = holder[0];

        if (html == null) {
            return null;
        }
        String text = lyricsText(html);
        if (text == null || text.isEmpty()) {
            return null;
        }
        LRCParser.PlainLyrics parsed = LRCParser.parsePlainWithSlots(text);
        List<LyricsLine> lines = parsed.lines();
        if (lines.isEmpty()) {
            return null;
        }

        List<String> credits = record != null ? credits(record) : null;
        Map<String, List<LyricsLine>> translations = record != null
                ? translations(record, lines, text, parsed.slots())
                : null;

        return FetchResult.of(
                new Lyrics(lines, "Genius", false, null, translations, null,
                        credits, text, "txt", url, null),
                title, artist, 0L, track);
    }

    /**
     * The first hit the shared matcher accepts. Keeping that one rather than the highest score
     * is what holds the lookup to a single page request.
     */
    @Nullable
    private static JSONObject findBestHit(TrackInfo track) throws Exception {
        String term = track.artist() + " " + track.title();
        String url = SEARCH + LyricsRequests.encode(term);
        JSONObject root;
        HttpURLConnection connection = null;
        try {
            connection = openGenius(url, true);
            final int code = connection.getResponseCode();
            if (code != 200) {
                Logger.printDebug(() -> "Genius search returned HTTP " + code);
                return null;
            }
            root = LyricsRequests.parseGzipJsonObject(connection);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not search Genius", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        JSONObject response = root.optJSONObject("response");
        JSONArray sections = response != null ? response.optJSONArray("sections") : null;
        if (sections == null) {
            return null;
        }
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (section == null || !SONG_SECTIONS.contains(section.optString("type", ""))) {
                continue;
            }
            JSONArray hits = section.optJSONArray("hits");
            if (hits == null) {
                continue;
            }
            for (int j = 0; j < hits.length(); j++) {
                JSONObject wrapper = hits.optJSONObject(j);
                JSONObject result = wrapper != null ? wrapper.optJSONObject("result") : null;
                if (result == null || !isSong(result)) {
                    continue;
                }
                int score = LyricsRequests.scoreTrackCandidate(
                        result.optString("title", ""), primaryArtistName(result), 0L, track);
                if (score < LyricsRequests.SOFT_MIN) {
                    continue;
                }
                return result;
            }
        }
        return null;
    }

    /** A hit that is not a song shares the id space but has no lyric page. */
    private static boolean isSong(JSONObject hit) {
        String type = hit.optString("_type", hit.optString("type", ""));
        return type.isEmpty() || "song".equals(type);
    }

    private static String primaryArtistName(JSONObject hit) {
        JSONObject artist = hit.optJSONObject("primary_artist");
        return artist != null ? artist.optString("name", "") : "";
    }

    /**
     * The credits, one entry per row, in the order the song's page lists them: the release date,
     * then the producers and the writer, then every custom performance. Each row is the label the
     * page shows followed by the names under it.
     */
    @Nullable
    private static List<String> credits(JSONObject record) {
        List<String> credits = new ArrayList<>();

        String released = record.optString("release_date_for_display", "").trim();
        if (!released.isEmpty()) {
            credits.add("Released on: " + released);
        }
        addCredit(credits, "Producers", record.optJSONArray("producer_artists"));
        addCredit(credits, "Writer", record.optJSONArray("writer_artists"));

        JSONArray performances = record.optJSONArray("custom_performances");
        if (performances != null) {
            for (int i = 0; i < performances.length(); i++) {
                JSONObject performance = performances.optJSONObject(i);
                if (performance == null) {
                    continue;
                }
                addCredit(credits, performance.optString("label", "").trim(),
                        performance.optJSONArray("artists"));
            }
        }

        return credits.isEmpty() ? null : credits;
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static void addCredit(List<String> credits, String label,
                                  @Nullable JSONArray artists) {
        if (artists == null) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) {
                continue;
            }
            String name = artist.optString("name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(name);
        }
        if (names.length() == 0) {
            return;
        }
        // An empty label means the page shows the names on their own.
        credits.add(label.isEmpty() ? names.toString() : label + ": " + names);
    }

    /**
     * The translation Genius files under the song, line-aligned to the lyrics on hand. Genius keeps
     * each translator's page as a separate song, so the one that pairs with this page is the one
     * worth reading: a page whose sections or line counts disagree is a cover, or a translation of
     * some other song, and pairing it would put lines under the wrong words.
     */
    @Nullable
    private static Map<String, List<LyricsLine>> translations(JSONObject record,
                                                             List<LyricsLine> lines, String text,
                                                             int[] slots) {
        String target = LyricsRequests.primarySubtag(LyricsRequests.translationLanguage());
        if (target.isEmpty()) {
            return null;
        }
        JSONArray linked = record.optJSONArray("translation_songs");
        if (linked == null || linked.length() == 0) {
            return null;
        }
        String wanted = matchingPage(linked, target);
        if (wanted == null) {
            return null;
        }
        String html = fetchPage(wanted);
        if (html == null) {
            return null;
        }
        String translatedText = lyricsText(html);
        if (translatedText == null || translatedText.isEmpty()) {
            return null;
        }
        List<String> aligned = align(text, translatedText, lines.size(), slots);
        if (aligned == null) {
            return null;
        }
        List<LyricsLine> out = new ArrayList<>(aligned.size());
        boolean any = false;
        for (String value : aligned) {
            if (!value.isEmpty()) {
                any = true;
            }
            out.add(new LyricsLine(LyricsLine.NO_TIME, value));
        }
        if (!any) {
            return null;
        }
        return LyricsMerge.singleLanguageTranslations(out, target);
    }

    @Nullable
    private static String matchingPage(JSONArray linked, String target) {
        String best = null;
        boolean bestIsTranslation = false;
        for (int i = 0; i < linked.length(); i++) {
            JSONObject entry = linked.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String url = entry.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            String title = entry.optString("title", "");
            if (COVER.matcher(title).find()) {
                continue;
            }
            if (!target.equals(LyricsRequests.primarySubtag(
                    languageCode(entry.optString("language", ""))))) {
                continue;
            }
            boolean isTranslation = TRANSLATION.matcher(title).find();
            if (best == null || (isTranslation && !bestIsTranslation)) {
                best = url;
                bestIsTranslation = isTranslation;
            }
        }
        return best;
    }

    /**
     * Pairs each line of {@code original} with its translation.
     *
     * @param slots For each line of {@code original}, the index it became in the parsed lyrics,
     *              or {@code -1} for a heading that was dropped. A raw line index is therefore
     *              not an index into the parsed lines, and every write below has to go through it.
     */
    @Nullable
    private static List<String> align(String original, String translation, int lineCount,
                                      int[] slots) {
        List<Section> ours = sections(original);
        List<Section> theirs = sections(translation);
        List<String> out = new ArrayList<>(Collections.nCopies(lineCount, ""));
        if (ours.size() != theirs.size() || ours.isEmpty()) {
            // No shared structure to pair on, so the pages are read whole: every content line on
            // one answers to the content line in the same place on the other, headers aside.
            List<String> mine = content(original);
            List<String> theirsLines = content(translation);
            if (mine.isEmpty() || mine.size() != theirsLines.size()) {
                return null;
            }
            int[] contentLines = contentSlots(original);
            for (int j = 0; j < contentLines.length && j < lineCount; j++) {
                int at = lineOf(contentLines[j], slots, lineCount);
                if (at >= 0) {
                    out.set(at, theirsLines.get(j));
                }
            }
            return out;
        }
        String[] raw = original.split("\\r?\\n", -1);
        int paired = 0;
        for (int s = 0; s < ours.size(); s++) {
            Section mine = ours.get(s);
            Section theirsLines = theirs.get(s);
            if (mine.lines().size() != theirsLines.lines().size()) {
                continue;
            }
            int slot = 0;
            for (int i = mine.start(); i < raw.length && slot < theirsLines.lines().size(); i++) {
                String text = raw[i].trim();
                if (isSectionHeader(text)) {
                    break;
                }
                if (text.isEmpty()) {
                    continue;
                }
                int at = lineOf(i, slots, lineCount);
                if (at < 0) {
                    break;
                }
                out.set(at, theirsLines.lines().get(slot++));
                paired++;
            }
        }
        return paired == 0 ? null : out;
    }

    /**
     * @return Where source line {@code rawIndex} ended up in the parsed lyrics, or {@code -1}
     *         when it has no line there because it was a heading, was past the end of the list,
     *         or the index was out of range.
     */
    private static int lineOf(int rawIndex, int[] slots, int lineCount) {
        if (rawIndex < 0 || rawIndex >= slots.length) {
            return -1;
        }
        int at = slots[rawIndex];
        return at >= 0 && at < lineCount ? at : -1;
    }

    private record Section(int start, List<String> lines) {
    }

    private static List<Section> sections(String text) {
        List<Section> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int start = 0;
        String[] raw = text.split("\\r?\\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String trimmed = raw[i].trim();
            if (isSectionHeader(trimmed)) {
                if (!current.isEmpty()) {
                    out.add(new Section(start, current));
                }
                current = new ArrayList<>();
            } else if (!trimmed.isEmpty()) {
                if (current.isEmpty()) {
                    start = i;
                }
                current.add(trimmed);
            }
        }
        if (!current.isEmpty()) {
            out.add(new Section(start, current));
        }
        return out;
    }

    private static List<String> content(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !isSectionHeader(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static int[] contentSlots(String text) {
        String[] raw = text.split("\\r?\\n", -1);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String trimmed = raw[i].trim();
            if (!trimmed.isEmpty() && !isSectionHeader(trimmed)) {
                slots.add(i);
            }
        }
        int[] out = new int[slots.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = slots.get(i);
        }
        return out;
    }

    private static boolean isSectionHeader(String trimmed) {
        return trimmed.length() > 2 && trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    @Nullable
    private static String fetchPage(String url) {
        try {
            return get(url, false, PAGE_READ_TIMEOUT_MS, LyricsRequests::parseGzipString);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the Genius song page", ex);
            return null;
        }
    }

    @Nullable
    private static JSONObject songRecord(long songId) {
        if (songId <= 0) {
            return null;
        }
        JSONObject cached = recordCache.get(songId);
        if (cached != null) {
            return cached;
        }
        Object lock = recordLocks.computeIfAbsent(songId, id -> new Object());
        try {
            synchronized (lock) {
                JSONObject again = recordCache.get(songId);
                if (again != null) {
                    return again;
                }
                try {
                    JSONObject song = get(SONG_RECORD + songId, true, RECORD_READ_TIMEOUT_MS,
                            LyricsRequests::parseGzipJsonObject);
                    if (song == null) {
                        return null;
                    }
                    JSONObject record = song.optJSONObject("response") != null
                            ? Objects.requireNonNull(song.optJSONObject("response"))
                            .optJSONObject("song") : null;
                    if (record != null) {
                        recordCache.put(songId, record);
                    }
                    return record;
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Could not read the Genius song record", ex);
                    return null;
                }
            }
        } finally {
            recordLocks.remove(songId, lock);
        }
    }

    @Nullable
    private static <T> T get(String url, boolean json, int readTimeoutMs, Reader<T> reader)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            HttpURLConnection connection = null;
            try {
                connection = openGenius(url, json);
                connection.setReadTimeout(readTimeoutMs);
                int code = connection.getResponseCode();
                if (code == 200) {
                    return reader.read(connection);
                }
                if (attempt == 0) {
                    if (code == 429) {
                        long wait = retryAfterMs(connection);
                        Logger.printDebug(
                                () -> "Genius rate limited, retrying in " + wait + "ms");
                        if (sleepBeforeRetry(wait)) {
                            continue;
                        }
                    } else if (code == 403) {
                        Logger.printDebug(
                                () -> "Genius refused the request, retrying in "
                                        + RETRY_403_WAIT_MS + "ms");
                        if (sleepBeforeRetry(RETRY_403_WAIT_MS)) {
                            continue;
                        }
                    }
                }
                Logger.printDebug(() -> "Genius returned HTTP " + code + " for " + url);
                return null;
            } catch (IOException ex) {
                // A reset or timed-out connection is worth one more try, but only while the
                // lookup still wants the answer: the manager cancels a stage by interrupting,
                // and fighting that cancellation would hold the thread past its deadline.
                if (attempt == 0 && sleepBeforeRetry(RETRY_IO_WAIT_MS)) {
                    continue;
                }
                throw ex;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not read " + url, ex);
                return null;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    /** True when the wait finished and the caller may retry; false when the thread was canceled. */
    private static boolean sleepBeforeRetry(long waitMs) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !Thread.currentThread().isInterrupted();
    }

    private interface Reader<T> {
        @Nullable
        T read(HttpURLConnection connection) throws Exception;
    }

    /** The wait Genius asks for, capped so a hostile header cannot stall the lookup. */
    private static long retryAfterMs(HttpURLConnection connection) {
        String value = connection.getHeaderField("Retry-After");
        long ms = 0;
        if (value != null) {
            try {
                ms = Long.parseLong(value.trim()) * 1000L;
            } catch (NumberFormatException ex) {
                // Retry-After may also be an HTTP date, which falls back to the default wait.
                Logger.printDebug(() -> "Genius sent a Retry-After that is not seconds: " + value, ex);
            }
        }
        if (ms <= 0) {
            ms = 1000;
        }
        return Math.min(ms, RETRY_AFTER_CEILING_MS);
    }

    private static HttpURLConnection openGenius(String url, boolean json)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BROWSER_UA);
        if (json) {
            headers.put("Accept", "application/json");
            headers.put("Referer", "https://genius.com/");
        }
        return LyricsRequests.openConnection(url, headers);
    }

    /**
     * The text of every {@code data-lyrics-container} div. They nest further divs, some of which
     * are page furniture rather than lyrics (a translations menu, annotation links), so nesting is
     * tracked and anything inside {@code data-exclude-from-selection} is dropped.
     */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    @Nullable
    static String lyricsText(@Nullable String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            Matcher open = CONTAINER.matcher(html);
            if (!open.find(from)) {
                break;
            }
            StringBuilder container = new StringBuilder();
            int depth = 1;
            int excludedAt = -1;
            int at = open.end();
            while (depth > 0) {
                Matcher tag = TAG.matcher(html);
                if (!tag.find(at)) {
                    break;
                }
                if (excludedAt < 0) {
                    container.append(html, at, tag.start());
                }
                at = tag.end();
                String name = Objects.requireNonNull(tag.group(2)).toLowerCase(Locale.ROOT);
                boolean closing = Objects.equals(tag.group(1), "/");
                if ("br".equals(name)) {
                    if (excludedAt < 0) {
                        container.append('\n');
                    }
                    continue;
                }
                if (!NESTING_TAGS.contains(name)
                        || Objects.requireNonNull(tag.group(3)).trim().endsWith("/")) {
                    continue;
                }
                if (closing) {
                    depth--;
                    if (depth == excludedAt) {
                        excludedAt = -1;
                    }
                } else {
                    if (excludedAt < 0
                            && Objects.requireNonNull(tag.group(3)).contains(
                                    "data-exclude-from-selection=\"true\"")) {
                        excludedAt = depth;
                    }
                    depth++;
                }
            }
            if (container.length() > 0) {
                out.append(container).append('\n');
            }
            from = at;
        }
        String decoded = out.toString()
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&quot;", "\"");
        String[] split = decoded.split("\n", -1);
        StringBuilder joined = new StringBuilder(decoded.length());
        for (String line : split) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(line.trim());
        }
        return BLANK_RUN.matcher(joined).replaceAll("\n\n").trim();
    }
}
