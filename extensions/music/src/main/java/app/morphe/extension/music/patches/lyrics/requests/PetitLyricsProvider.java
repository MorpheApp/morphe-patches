/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class PetitLyricsProvider implements LyricsProvider {

    private static final String API_URL = "https://p0.petitlyrics.com/api/GetPetitLyricsData.php";
    private static final String CLIENT_APP_ID = "p1110417";
    private static final String TERMINAL_TYPE = "10";
    private static final String SOURCE_URL_PREFIX = "https://petitlyrics.com/lyrics/";

    private static final long REQUEST_THROTTLE_MS = 500;
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static final int TIER_UNSYNCED = 1;
    private static final int TIER_LINE_SYNC = 2;
    private static final int TIER_WORD_SYNC = 3;

    private static final int LSY_KEY_SWITCH_FLAG_OFF = 0x19;
    private static final int LSY_PROTECTION_ID_OFF = 0x1a;
    private static final int LSY_LINE_COUNT_OFF = 0x38;
    private static final int LSY_LINE_LENGTH_OFF = 0x42;
    private static final int LSY_PAYLOAD_START = 0xcc;
    private static final int LSY_MIN_HEADER_LEN = LSY_PAYLOAD_START;
    private static final int LSY_MAX_LINES = 10_000;
    private static final int LSY_WRAP_CS = 65_536;

    @Override
    public String name() {
        return "PetitLyrics";
    }

    @Override
    public boolean hasCandidates() {
        return false;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        Stash stash = new Stash();

        collectTier(track, TIER_WORD_SYNC, stash);
        Lyrics result = tryWordSync(stash);
        if (result != null) {
            return result;
        }

        collectTier(track, TIER_LINE_SYNC, stash);
        result = tryWordSync(stash);
        if (result != null) {
            return result;
        }
        result = tryLineSync(track, stash);
        if (result != null) {
            return result;
        }

        if (stash.plainText == null && !stash.plainRequested) {
            collectTier(track, TIER_UNSYNCED, stash);
        }
        if (stash.plainText == null && stash.zipText != null) {
            stash.plainText = stash.zipText;
            if (stash.sourceLyricsId == null) {
                stash.sourceLyricsId = stash.zipLyricsId;
            }
        }
        result = tryText(stash);
        if (result != null) {
            return result;
        }
        return null;
    }

    private void collectTier(TrackInfo track, int lyricsType, Stash stash) {
        if (lyricsType == TIER_UNSYNCED) {
            if (stash.plainRequested) {
                return;
            }
            stash.plainRequested = true;
        }
        try {
            Response response = request(track, lyricsType);
            collect(response, track, stash);
        } catch (Exception ex) {
            Logger.printDebug(
                    () -> "PetitLyrics request failed for type " + lyricsType, ex);
        }
    }

    private void collect(@Nullable Response response, TrackInfo track, Stash stash) {
        if (response == null || response.songs.isEmpty()) {
            return;
        }
        List<Song> sorted = new ArrayList<>(response.songs);
        sorted.sort((a, b) -> Integer.compare(scoreOf(b, track), scoreOf(a, track)));

        if (stash.creditLines == null) {
            for (Song song : sorted) {
                List<String> credits = creditLinesOf(song);
                if (!credits.isEmpty()) {
                    stash.creditLines = credits;
                    if (stash.sourceLyricsId == null) {
                        stash.sourceLyricsId = song.lyricsId;
                    }
                    break;
                }
            }
        }

        if (stash.lsyLyricsId != null && stash.zipText == null) {
            Song idMatch = null;
            for (Song song : sorted) {
                if (song.lyricsData == null || song.lyricsData.isEmpty()) {
                    continue;
                }
                if (stash.lsyLyricsId.equals(song.lyricsId)) {
                    idMatch = song;
                    break;
                }
            }
            if (idMatch == null && response.songs.size() == 1) {
                idMatch = response.songs.get(0);
            }
            if (idMatch != null && idMatch.lyricsData != null
                    && !idMatch.lyricsData.isEmpty()) {
                try {
                    byte[] payload = Base64.decode(idMatch.lyricsData, Base64.DEFAULT);
                    if (payload != null && payload.length > 0
                            && classifyPayload(payload) == TIER_UNSYNCED) {
                        String text = toUtf8Raw(payload);
                        if (!text.trim().isEmpty()) {
                            stash.zipText = text;
                            stash.zipLyricsId = idMatch.lyricsId;
                            if (stash.sourceLyricsId == null) {
                                stash.sourceLyricsId = idMatch.lyricsId;
                            }
                        }
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "PetitLyrics zip text decode failed", ex);
                }
            }
        }

        for (Song song : sorted) {
            if (song.lyricsData == null || song.lyricsData.isEmpty()) {
                continue;
            }
            byte[] payload;
            try {
                payload = Base64.decode(song.lyricsData, Base64.DEFAULT);
            } catch (Exception ex) {
                Logger.printDebug(() -> "PetitLyrics base64 decode failed", ex);
                continue;
            }
            if (payload == null || payload.length == 0) {
                continue;
            }
            int tier = classifyPayload(payload);
            switch (tier) {
                case TIER_WORD_SYNC -> {
                    if (stash.wordLines == null) {
                        List<LyricsLine> lines = parseWsy(payload);
                        if (!lines.isEmpty()) {
                            stash.wordLines = lines;
                            stash.wsyRaw = toUtf8(payload);
                            stash.sourceLyricsId = song.lyricsId;
                        }
                    }
                }
                case TIER_LINE_SYNC -> {
                    if (stash.lsyTimings == null) {
                        List<Long> timings = decodeLsyTimings(payload);
                        if (!timings.isEmpty()) {
                            stash.lsyTimings = timings;
                            stash.lsyLyricsId = song.lyricsId;
                            stash.sourceLyricsId = song.lyricsId;
                        }
                    }
                }
                default -> {
                    if (stash.plainText == null) {
                        String text = toUtf8Raw(payload);
                        if (!text.trim().isEmpty()) {
                            stash.plainText = text;
                            stash.sourceLyricsId = song.lyricsId;
                        }
                    }
                }
            }
        }
    }

    private static int scoreOf(Song song, TrackInfo track) {
        return LyricsRequests.scoreTrackCandidate(
                song.title, song.artist, song.durationMs / 1000, track);
    }

    @Nullable
    private Lyrics tryWordSync(Stash stash) {
        if (stash.wordLines == null || stash.wordLines.isEmpty()) {
            return null;
        }
        String wsyRaw = stash.wsyRaw != null && !stash.wsyRaw.isEmpty()
                ? stash.wsyRaw
                : null;
        return new Lyrics(stash.wordLines, name(), true, null, null, null,
                stash.creditLines,
                wsyRaw, wsyRaw != null ? "wsy" : null, sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private Lyrics tryLineSync(TrackInfo track, Stash stash) {
        if (stash.lsyTimings == null || stash.lsyTimings.isEmpty()) {
            return null;
        }
        if (stash.zipText == null) {
            collectTier(track, TIER_UNSYNCED, stash);
            if (stash.zipText == null) {
                return null;
            }
        }
        List<LyricsLine> lines = zipLineSync(stash.lsyTimings, stash.zipText);
        if (lines.isEmpty()) {
            return null;
        }
        String lrc = buildLrc(lines);
        return new Lyrics(lines, name(), true, null, null, null, stash.creditLines,
                lrc, "lrc", sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private Lyrics tryText(Stash stash) {
        if (stash.plainText == null || stash.plainText.isEmpty()) {
            return null;
        }
        String text = stash.plainText;
        LrcParser.LrcParseResult lrc = LrcParser.parseSyncedWithCreditLines(text);
        if (!lrc.lines.isEmpty()) {
            List<String> credits = mergeCredits(stash.creditLines, lrc.creditLines);
            return new Lyrics(lrc.lines, name(), true, null, null, null,
                    credits.isEmpty() ? null : credits,
                    text, "lrc", sourceUrl(stash.sourceLyricsId));
        }
        List<LyricsLine> plain = LrcParser.parsePlain(text);
        if (plain.isEmpty()) {
            return null;
        }
        return new Lyrics(plain, name(), false, null, null, null, stash.creditLines,
                text, "txt", sourceUrl(stash.sourceLyricsId));
    }

    @Nullable
    private static String sourceUrl(@Nullable String lyricsId) {
        if (lyricsId == null || lyricsId.trim().isEmpty()) {
            return null;
        }
        return SOURCE_URL_PREFIX + lyricsId.trim();
    }

    @Nullable
    private static List<String> creditLinesOf(Song song) {
        List<String> credits = new ArrayList<>(4);
        addCredit(credits, "Artist", song.artist);
        addCredit(credits, "Album", song.album);
        addCredit(credits, "Writer", song.writer);
        addCredit(credits, "Composer", song.composer);
        return credits;
    }

    private static void addCredit(List<String> credits, String label, @Nullable String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        credits.add(label + ": " + trimmed);
    }

    @Nullable
    private static List<String> mergeCredits(@Nullable List<String> primary,
            @Nullable List<String> secondary) {
        if (primary == null || primary.isEmpty()) {
            return secondary == null || secondary.isEmpty() ? null : secondary;
        }
        if (secondary == null || secondary.isEmpty()) {
            return primary;
        }
        List<String> merged = new ArrayList<>(primary.size() + secondary.size());
        merged.addAll(primary);
        for (String line : secondary) {
            if (!merged.contains(line)) {
                merged.add(line);
            }
        }
        return merged;
    }

    @Nullable
    private Response request(TrackInfo track, int lyricsType) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        StringBuilder form = new StringBuilder();
        form.append("clientAppId=").append(LyricsRequests.encode(CLIENT_APP_ID));
        form.append("&terminalType=").append(LyricsRequests.encode(TERMINAL_TYPE));
        form.append("&lyricsType=").append(lyricsType);
        form.append("&key_title=").append(LyricsRequests.encode(track.title()));
        form.append("&key_artist=").append(LyricsRequests.encode(track.artist()));
        form.append("&key_album=").append(LyricsRequests.encode(track.album()));

        var connection = LyricsRequests.postForm(API_URL, form.toString());
        int httpCode = connection.getResponseCode();
        if (httpCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        String xml;
        try {
            xml = LyricsRequests.parseGzipString(connection);
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics response read failed", ex);
            connection.disconnect();
            return null;
        }
        return parseResponse(xml);
    }

    @Nullable
    private static Response parseResponse(String xml) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                    "UTF-8");

            Response response = new Response();
            Song current = null;
            StringBuilder text = new StringBuilder();
            String field = null;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG -> {
                        field = parser.getName();
                        if ("song".equals(field)) {
                            current = new Song();
                        } else {
                            text.setLength(0);
                        }
                    }
                    case XmlPullParser.TEXT -> {
                        if (field != null && current != null) {
                            text.append(parser.getText());
                        } else if (field != null && "status".equals(field)) {
                            text.append(parser.getText());
                        }
                    }
                    case XmlPullParser.END_TAG -> {
                        String tag = parser.getName();
                        if (current != null && "song".equals(tag)) {
                            if (current.lyricsData != null && !current.lyricsData.isEmpty()) {
                                response.songs.add(current);
                            }
                            current = null;
                        } else if (current != null && field != null) {
                            String value = text.toString();
                            switch (field) {
                                case "status" -> response.status = value;
                                case "lyricsId" -> current.lyricsId = value;
                                case "title" -> current.title = value;
                                case "artist" -> current.artist = value;
                                case "album" -> current.album = value;
                                case "writer" -> current.writer = value;
                                case "composer" -> current.composer = value;
                                case "duration" -> {
                                    try {
                                        current.durationMs = Long.parseLong(value.trim());
                                    } catch (NumberFormatException ignored) {
                                        current.durationMs = 0;
                                    }
                                }
                                case "lyricsType" -> {
                                    try {
                                        current.lyricsType = Integer.parseInt(value.trim());
                                    } catch (NumberFormatException ignored) {
                                        current.lyricsType = 0;
                                    }
                                }
                                case "lyricsData" -> current.lyricsData = value.trim();
                                default -> { }
                            }
                        } else if ("status".equals(tag)) {
                            response.status = text.toString();
                        }
                        field = null;
                    }
                    default -> { }
                }
                event = parser.next();
            }
            if (!"00000000".equals(response.status)) {
                return null;
            }
            return response;
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics XML parse failed", ex);
            return null;
        }
    }

    private static int classifyPayload(byte[] raw) {
        byte[] root = xmlRootPrefix(raw);
        if (root.length >= 4 && root[0] == '<' && root[1] == 'w' && root[2] == 's'
                && root[3] == 'y') {
            return TIER_WORD_SYNC;
        }
        if (containsNul(raw) || !isValidUtf8(raw)) {
            return TIER_LINE_SYNC;
        }
        return TIER_UNSYNCED;
    }

    private static byte[] xmlRootPrefix(byte[] raw) {
        int i = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < raw.length) {
            byte b = raw[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                i++;
                continue;
            }
            if (i + 1 < raw.length && raw[i] == '<' && raw[i + 1] == '?') {
                int end = indexOf(raw, "?>".getBytes(StandardCharsets.US_ASCII), i);
                if (end < 0) {
                    break;
                }
                i = end + 2;
                continue;
            }
            if (i + 3 < raw.length && raw[i] == '<' && raw[i + 1] == '!'
                    && raw[i + 2] == '-' && raw[i + 3] == '-') {
                int end = indexOf(raw, "-->".getBytes(StandardCharsets.US_ASCII), i);
                if (end < 0) {
                    break;
                }
                i = end + 3;
                continue;
            }
            break;
        }
        byte[] rest = new byte[raw.length - i];
        System.arraycopy(raw, i, rest, 0, rest.length);
        return rest;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean containsNul(byte[] raw) {
        for (byte b : raw) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidUtf8(byte[] raw) {
        try {
            StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(raw));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<LyricsLine> parseWsy(byte[] payload) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(payload), "UTF-8");

            List<LyricsLine> lines = new ArrayList<>();
            List<Word> words = new ArrayList<>();
            StringBuilder lineText = new StringBuilder();
            StringBuilder wordText = new StringBuilder();
            long wordStart = 0;
            long wordEnd = 0;
            String tag = null;
            boolean inLine = false;
            boolean inWord = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG -> {
                        tag = parser.getName();
                        if ("line".equals(tag)) {
                            inLine = true;
                            words.clear();
                            lineText.setLength(0);
                        } else if (inLine && "word".equals(tag)) {
                            inWord = true;
                            wordText.setLength(0);
                            wordStart = 0;
                            wordEnd = 0;
                        }
                    }
                    case XmlPullParser.TEXT -> {
                        if (tag == null) {
                            break;
                        }
                        if (inWord) {
                            switch (tag) {
                                case "wordstring" -> wordText.append(parser.getText());
                                case "starttime" -> wordStart = parseLong(parser.getText());
                                case "endtime" -> wordEnd = parseLong(parser.getText());
                                default -> { }
                            }
                        } else if (inLine && "linestring".equals(tag)) {
                            lineText.append(parser.getText());
                        }
                    }
                    case XmlPullParser.END_TAG -> {
                        String end = parser.getName();
                        if (inWord && "word".equals(end)) {
                            inWord = false;
                            String text = wordText.toString();
                            if (!text.isEmpty()) {
                                long start = Math.max(0, wordStart);
                                long endMs = Math.max(start, wordEnd);
                                words.add(new Word(start, endMs, text));
                            }
                        } else if (inLine && "line".equals(end)) {
                            inLine = false;
                            if (words.isEmpty()) {
                                break;
                            }
                            String text = lineText.toString().trim();
                            if (text.isEmpty()) {
                                StringBuilder joined = new StringBuilder();
                                for (Word w : words) {
                                    joined.append(w.text());
                                }
                                text = joined.toString().trim();
                            }
                            if (text.isEmpty()) {
                                break;
                            }
                            long start = words.get(0).startMs();
                            long endMs = words.get(words.size() - 1).endMs();
                            lines.add(new LyricsLine(start, endMs, text, new ArrayList<>(words)));
                        }
                        tag = null;
                    }
                    default -> { }
                }
                event = parser.next();
            }
            lines.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
            return lines;
        } catch (Exception ex) {
            Logger.printDebug(() -> "PetitLyrics WSY parse failed", ex);
            return List.of();
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static List<Long> decodeLsyTimings(byte[] raw) {
        if (raw.length < LSY_MIN_HEADER_LEN) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int lineCount = buf.getInt(LSY_LINE_COUNT_OFF);
        if (lineCount <= 0 || lineCount > LSY_MAX_LINES) {
            return List.of();
        }
        int end = LSY_PAYLOAD_START + 2 * lineCount;
        if (raw.length < end) {
            return List.of();
        }
        int lineLength = buf.getShort(LSY_LINE_LENGTH_OFF) & 0xFFFF;
        if (lineLength > 0) {
            int want = LSY_PAYLOAD_START + 2 * lineCount + lineLength * lineCount;
            if (raw.length != want) {
                return List.of();
            }
        }
        boolean switchFlag = raw[LSY_KEY_SWITCH_FLAG_OFF] != 0;
        int protectionId = buf.getShort(LSY_PROTECTION_ID_OFF) & 0xFFFF;
        int key = deriveLsyKey(protectionId, switchFlag);

        List<Long> out = new ArrayList<>(lineCount);
        int wraps = 0;
        int prev = 0;
        for (int i = 0; i < lineCount; i++) {
            int cs = (buf.getShort(LSY_PAYLOAD_START + 2 * i) & 0xFFFF) ^ key;
            if (i > 0 && cs < prev) {
                wraps++;
            }
            prev = cs;
            out.add((long) (cs + wraps * LSY_WRAP_CS));
        }
        return out;
    }

    private static int deriveLsyKey(int protectionId, boolean switchFlag) {
        if (!switchFlag) {
            return protectionId;
        }
        int k = protectionId;
        return (k & 0x0003)
                | ((k & 0x000c) << 2)
                | ((k & 0x0030) >> 2)
                | ((k & 0x00c0) << 2)
                | ((k & 0x0300) >> 2)
                | ((k & 0x0c00) << 2)
                | ((k & 0x3000) >> 2)
                | (k & 0xc000);
    }

    private static List<LyricsLine> zipLineSync(List<Long> timingsCs, String text) {
        String normalized = text.replace("\r\n", "\n");
        String[] rows = normalized.split("\n", -1);
        int count = rows.length;
        if (count != timingsCs.size()) {
            while (count > 0 && rows[count - 1].trim().isEmpty()) {
                count--;
            }
        }
        if (count != timingsCs.size()) {
            final int finalCount = count;
            Logger.printDebug(() -> "PetitLyrics line-sync mismatch: "
                    + timingsCs.size() + " timings vs " + finalCount + " lines");
            return List.of();
        }
        List<LyricsLine> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String row = rows[i].trim();
            if (row.isEmpty()) {
                continue;
            }
            long startMs = timingsCs.get(i) * 10;
            long endMs = (i + 1 < count) ? timingsCs.get(i + 1) * 10 : startMs + 3000;
            if (endMs <= startMs) {
                endMs = startMs + 3000;
            }
            lines.add(new LyricsLine(startMs, endMs, row, List.of()));
        }
        return lines;
    }

    private static String buildLrc(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder(50 * lines.size());
        for (LyricsLine line : lines) {
            final long totalMs = Math.max(0, line.startTimeMs());
            final long min = totalMs / 60000;
            final long sec = (totalMs % 60000) / 1000;
            final long ms = totalMs % 1000;
            sb.append('[')
              .append(String.format(Locale.US, "%02d:%02d.%02d", min, sec, ms / 10))
              .append(']')
              .append(line.text())
              .append('\n');
        }
        return sb.toString();
    }

    private static String toUtf8(byte[] payload) {
        try {
            return new String(payload, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String toUtf8Raw(byte[] payload) {
        try {
            return new String(payload, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    private static final class Stash {
        @Nullable
        List<LyricsLine> wordLines;
        @Nullable
        String wsyRaw;
        @Nullable
        List<Long> lsyTimings;
        @Nullable
        String lsyLyricsId;
        @Nullable
        String zipText;
        @Nullable
        String zipLyricsId;
        @Nullable
        String plainText;
        boolean plainRequested;
        @Nullable
        List<String> creditLines;
        @Nullable
        String sourceLyricsId;
    }

    private static final class Response {
        String status;
        final List<Song> songs = new ArrayList<>();
    }

    private static final class Song {
        String lyricsId;
        String title;
        String artist;
        String album;
        String writer;
        String composer;
        long durationMs;
        int lyricsType;
        String lyricsData;
    }
}
