/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.requests.Requester;

public final class SimpMusicProvider implements LyricsProvider {

    private static final String BASE_URL = "https://api-lyrics.simpmusic.org/v1/";
    private static final Pattern HTML_ENTITY =
            Pattern.compile("&#x([0-9a-fA-F]+);|&#(\\d+);");

    @Override
    public String name() {
        return "SimpMusic";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String videoId = VideoInformation.getVideoId();
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }
        return fetchByVideoId(videoId);
    }

    @Nullable
    private Lyrics fetchByVideoId(String videoId) {
        HttpURLConnection connection = null;
        try {
            final String url = BASE_URL + LyricsRequests.encode(videoId);
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("Accept", "application/json");

            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);
            if (!root.optBoolean("success", false)) {
                return null;
            }
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            return pickBestEntry(data);
        } catch (IOException | JSONException ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics pickBestEntry(JSONArray data) {
        Lyrics best = null;
        int bestVote = Integer.MIN_VALUE;
        for (int i = 0; i < data.length(); i++) {
            final JSONObject entry = data.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            final int vote = entry.optInt("vote", 0);
            if (vote < bestVote) {
                continue;
            }
            final Lyrics lyrics = parseEntry(entry);
            if (lyrics != null && !lyrics.isEmpty()) {
                best = lyrics;
                bestVote = vote;
            }
        }
        return best;
    }

    @Nullable
    private Lyrics parseEntry(JSONObject entry) {
        // Prefer rich sync (word-level), then synced (line-level), then plain.
        final String richSync = LyricsRequests.optString(entry, "richSyncLyrics");
        if (richSync != null) {
            return parseLrc(richSync);
        }
        final String synced = LyricsRequests.optString(entry, "syncedLyrics");
        if (synced != null) {
            return parseLrc(synced);
        }
        final String plain = LyricsRequests.optString(entry, "plainLyric");
        if (plain != null) {
            final List<LyricsLine> lines = LrcParser.parsePlain(plain);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, name(), false,
                        null, null, null, null, plain, "plain", null);
            }
        }
        return null;
    }

    @Nullable
    private Lyrics parseLrc(String lrc) {
        final long[] offsetHolder = {0};
        final String sanitized = sanitizeLrc(lrc, offsetHolder);
        final LrcParser.LrcParseResult result = LrcParser.parseSyncedWithCreditLines(sanitized);
        if (result.lines.isEmpty()) {
            return null;
        }
        List<LyricsLine> lines = collapseSpaces(result.lines);
        if (offsetHolder[0] != 0) {
            lines = applyOffset(lines, offsetHolder[0]);
        }
        return new Lyrics(lines, name(), true,
                null, null, null,
                result.creditLines.isEmpty() ? null : result.creditLines,
                sanitized, "lrc", null);
    }

    private static String sanitizeLrc(String lrc, long[] offsetHolder) {
        String[] rawLines = lrc.split("\\r?\\n");
        StringBuilder sb = new StringBuilder(lrc.length());

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("[offset:")) {
                int close = trimmed.indexOf(']');
                if (close > 0) {
                    String val = trimmed.substring(8, close).trim();
                    try {
                        offsetHolder[0] = (long) (Double.parseDouble(val) * 1000);
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            sb.append(rawLine).append('\n');
        }

        String result = decodeHtmlEntities(sb.toString());
        result = result.replaceAll("[ \t]{2,}", " ");
        result = result.replaceAll(">[ \t]+<", "><");
        result = result.replaceAll(">[ \t]+", ">");
        return result.trim();
    }

    private static String decodeHtmlEntities(String text) {
        Matcher matcher = HTML_ENTITY.matcher(text);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            builder.append(text, last, matcher.start());
            try {
                if (matcher.group(1) != null) {
                    builder.append((char) Integer.parseInt(matcher.group(1), 16));
                } else {
                    builder.append((char) Integer.parseInt(matcher.group(2)));
                }
            } catch (NumberFormatException ignored) {
                builder.append(matcher.group(0));
            }
            last = matcher.end();
        }
        builder.append(text, last, text.length());
        return builder.toString()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static List<LyricsLine> collapseSpaces(List<LyricsLine> lines) {
        List<LyricsLine> result = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            String text = line.text().replaceAll(" {2,}", " ").trim();
            if (!text.equals(line.text())) {
                result.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(), text, line.words(),
                        line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private static List<LyricsLine> applyOffset(List<LyricsLine> lines, long offsetMs) {
        List<LyricsLine> adjusted = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            long newStart = line.startTimeMs() + offsetMs;
            long newEnd = line.endTimeMs() != LyricsLine.NO_TIME
                    ? line.endTimeMs() + offsetMs : LyricsLine.NO_TIME;
            List<Word> adjustedWords = new ArrayList<>();
            if (line.words() != null) {
                for (Word word : line.words()) {
                    adjustedWords.add(new Word(
                            word.startMs() + offsetMs,
                            word.endMs() + offsetMs,
                            word.text(),
                            word.romaji(),
                            word.endsWithSpace()));
                }
            }
            adjusted.add(new LyricsLine(newStart, newEnd, line.text(), adjustedWords,
                    line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
        }
        return adjusted;
    }
}
