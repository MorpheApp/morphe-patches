/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;

/**
 * KuGou lyrics, used as a fallback because it covers many tracks LRCLIB does not.
 */
public final class KuGouProvider implements LyricsProvider {

    private static final String SEARCH_URL = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=";
    private static final String DOWNLOAD_URL = "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8";

    /**
     * KuGou lyrics frequently start with credit lines that are not part of the song.
     */
    private static final String[] CREDIT_LINE_MARKERS = {
            "作词", "作曲", "编曲", "制作人", "混音", "母带", "录音", "吉他", "贝斯", "鼓",
            "和声", "监制", "出品", "发行", "词：", "曲：", "唱：",
    };

    @NonNull
    @Override
    public String name() {
        return "KuGou";
    }

    @Nullable
    @Override
    public Lyrics fetch(@NonNull TrackInfo track) throws Exception {
        String keyword = track.artist() + " - " + track.title();
        StringBuilder searchUrl = new StringBuilder(SEARCH_URL);
        searchUrl.append("&keyword=").append(encode(keyword));
        if (track.durationSeconds() > 0) {
            searchUrl.append("&duration=").append(track.durationSeconds() * 1000L);
        }

        HttpURLConnection searchConnection = LyricsRequests.openConnection(searchUrl.toString());
        if (searchConnection.getResponseCode() != 200) {
            LyricsRequests.logFailure(name(), searchConnection);
            return null;
        }

        JSONObject searchResponse = LyricsRequests.parseJsonObjectAndDisconnect(searchConnection);
        JSONArray candidates = searchResponse.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return null;
        }

        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) {
            return null;
        }

        String id = candidate.optString("id", "");
        String accessKey = candidate.optString("accesskey", "");
        if (id.isEmpty() || accessKey.isEmpty()) {
            return null;
        }

        String downloadUrl = DOWNLOAD_URL + "&id=" + encode(id) + "&accesskey=" + encode(accessKey);
        HttpURLConnection downloadConnection = LyricsRequests.openConnection(downloadUrl);
        if (downloadConnection.getResponseCode() != 200) {
            LyricsRequests.logFailure(name(), downloadConnection);
            return null;
        }

        JSONObject downloadResponse = LyricsRequests.parseJsonObjectAndDisconnect(downloadConnection);
        String content = downloadResponse.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        String lrc = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8);
        List<LyricsLine> lines = removeCreditLines(LrcParser.parseSynced(lrc));
        if (lines.isEmpty()) {
            return null;
        }

        Logger.printDebug(() -> "KuGou returned " + lines.size() + " lines for " + track);
        return new Lyrics(lines, name(), true);
    }

    /**
     * Drops the leading credit lines. Only leading lines are checked so that a
     * lyric that happens to contain one of the markers is kept.
     */
    @NonNull
    private static List<LyricsLine> removeCreditLines(@NonNull List<LyricsLine> lines) {
        int firstLyric = 0;
        while (firstLyric < lines.size() && isCreditLine(lines.get(firstLyric).text())) {
            firstLyric++;
        }

        if (firstLyric == 0) {
            return lines;
        }
        // Every line being a credit line means the parse produced nothing usable.
        if (firstLyric == lines.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(lines.subList(firstLyric, lines.size()));
    }

    private static boolean isCreditLine(@NonNull String text) {
        if (text.isEmpty()) {
            return true;
        }
        for (String marker : CREDIT_LINE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Charset overload of encode() needs API 33, so the charset is named instead.
     */
    @NonNull
    @SuppressWarnings("CharsetObjectCanBeUsed")
    private static String encode(@NonNull String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
