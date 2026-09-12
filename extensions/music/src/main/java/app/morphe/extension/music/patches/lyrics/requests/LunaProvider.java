/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;


import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.requests.Requester;

public final class LunaProvider implements LyricsProvider {


    private static final String SEARCH_URL = "https://api.qishui.com/luna/search/track";
    private static final String DETAIL_URL = "https://beta-luna.douyin.com/luna/h5/seo_track";

    private static final String SEARCH_UA =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; Build/V417IR;tt-ok/3.12.13.19)";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final long REQUEST_THROTTLE_MS = 250;
    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    @Override
    public String name() {
        return "Luna";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        final JSONArray trackIds = searchTracks(track);
        if (trackIds == null || trackIds.length() == 0) {
            return java.util.Collections.emptyList();
        }

        final List<Lyrics> results = new ArrayList<>();
        for (int i = 0; i < trackIds.length() && results.size() < 5; i++) {
            final String trackId = trackIds.optString(i, null);
            if (trackId == null || trackId.isEmpty()) {
                continue;
            }
            try {
                final Lyrics lyrics = fetchLyricsByTrackId(trackId);
                if (lyrics != null) {
                    results.add(lyrics);
                }
            } catch (Exception ex) {
            }
        }
        return results;
    }

    @Nullable
    private JSONArray searchTracks(TrackInfo track) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        final String keyword = track.artist() + " " + track.title();
        final String deviceId = generateClientId();
        final String installId = generateClientId();

        final String url = SEARCH_URL
                + "?device_platform=android"
                + "&os=android"
                + "&ssmix=a"
                + "&cdid=46556f98-1720-4248-83da-62b74b60b46a"
                + "&channel=xiaomi_8478_64"
                + "&aid=386088"
                + "&app_name=luna"
                + "&version_code=100198030"
                + "&version_name=19.8.0"
                + "&manifest_version_code=100198030"
                + "&update_version_code=100198030"
                + "&resolution=1080*1920"
                + "&dpi=480"
                + "&device_type=ABR-AL80"
                + "&device_brand=HUAWEI"
                + "&language=zh"
                + "&os_api=35"
                + "&os_version=15"
                + "&ac=wifi"
                + "&device_model=ABR-AL80"
                + "&tz_name=Asia/Shanghai"
                + "&tz_offset=28800"
                + "&package=com.luna.music"
                + "&sim_region=cn"
                + "&iid=" + installId
                + "&device_id=" + deviceId
                + "&_rticket=" + System.currentTimeMillis()
                + "&q=" + LyricsRequests.encode(keyword)
                + "&cursor=0"
                + "&count=10";

        final HttpURLConnection connection = openConnection(url, SEARCH_UA);
        if (connection == null || connection.getResponseCode() != 200) {
            if (connection != null) {
                LyricsRequests.logFailure("Luna", connection);
            }
            return null;
        }

        final JSONObject response = Requester.parseJSONObject(connection);
        final JSONArray resultGroups = response.optJSONArray("result_groups");
        if (resultGroups == null || resultGroups.length() == 0) {
            return null;
        }

        final List<String> trackIdList = new ArrayList<>();
        for (int g = 0; g < resultGroups.length(); g++) {
            final JSONObject group = resultGroups.optJSONObject(g);
            if (group == null) continue;
            final JSONArray data = group.optJSONArray("data");
            if (data == null) continue;
            for (int d = 0; d < data.length(); d++) {
                final JSONObject item = data.optJSONObject(d);
                if (item == null) continue;
                final JSONObject meta = item.optJSONObject("meta");
                if (meta == null) continue;
                final String itemType = meta.optString("item_type", "");
                if (!"track".equals(itemType)) continue;
                final JSONObject entity = item.optJSONObject("entity");
                if (entity == null) continue;
                final JSONObject trackObj = entity.optJSONObject("track");
                if (trackObj == null) continue;
                final String id = trackObj.optString("id", null);
                if (id != null && !id.isEmpty()) {
                    trackIdList.add(id);
                }
            }
        }

        final JSONArray ids = new JSONArray();
        for (String id : trackIdList) {
            ids.put(id);
        }
        return ids;
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(String trackId) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        final String url = DETAIL_URL
                + "?track_id=" + LyricsRequests.encode(trackId)
                + "&device_platform=web";

        final HttpURLConnection connection = openConnection(url, WEB_UA);
        if (connection == null || connection.getResponseCode() != 200) {
            if (connection != null) {
                LyricsRequests.logFailure("Luna", connection);
            }
            return null;
        }

        final JSONObject response = Requester.parseJSONObject(connection);
        final JSONObject lyricInfo = response.optJSONObject("lyric");
        if (lyricInfo == null) {
            return null;
        }

        final String content = lyricInfo.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        final String type = lyricInfo.optString("type", "lrc");

        final List<LyricsLine> lines;
        final String formatType;

        if ("krc".equals(type)) {
            final List<LyricsLine> yrcLines = KrcParser.parse(content);
            if (!yrcLines.isEmpty()) {
                lines = yrcLines;
                formatType = "krc";
            } else {
                lines = LrcParser.parseSynced(content);
                formatType = lines.isEmpty() ? "txt" : "lrc";
            }
        } else {
            final List<LyricsLine> lrcLines = LrcParser.parseSynced(content);
            if (!lrcLines.isEmpty()) {
                lines = lrcLines;
                formatType = "lrc";
            } else {
                lines = LyricsRequests.parsePlainTextLines(content);
                formatType = "txt";
            }
        }

        if (lines.isEmpty()) {
            return null;
        }

        final Map<String, List<LyricsLine>> translations = parseTranslations(lyricInfo, lines);
        final List<String> songwriters = extractSongwriters(response);
        final String sourceUrl = "https://www.douyin.com/qishui/song/" + trackId;

        return new Lyrics(lines, name(), !"txt".equals(formatType), null,
                translations != null && !translations.isEmpty() ? translations : null,
                null, songwriters, content, formatType, sourceUrl);
    }

    @Nullable
    private Map<String, List<LyricsLine>> parseTranslations(JSONObject lyricInfo, List<LyricsLine> original) {
        final Map<String, List<LyricsLine>> result = new HashMap<>();

        final JSONObject langTranslations = lyricInfo.optJSONObject("lang_translations");
        if (langTranslations != null) {
            final java.util.Iterator<String> keys = langTranslations.keys();
            while (keys.hasNext()) {
                final String lang = keys.next();
                final JSONObject transObj = langTranslations.optJSONObject(lang);
                if (transObj == null) continue;
                final String transContent = transObj.optString("content", "");
                if (transContent.isEmpty()) continue;
                final String transType = transObj.optString("type", "lrc");

                final List<LyricsLine> transLines;
                if ("krc".equals(transType)) {
                    transLines = KrcParser.parse(transContent);
                } else {
                    transLines = LrcParser.parseSynced(transContent);
                }

                if (!transLines.isEmpty()) {
                    final List<LyricsLine> merged = LyricsMerge.mergeRomanization(original, transLines);
                    if (LyricsMerge.hasText(merged)) {
                        result.put(lang, merged);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            final String sysLang = Locale.getDefault().getLanguage();
            if ("zh".equals(sysLang)) {
                final JSONObject translations = lyricInfo.optJSONObject("translations");
                if (translations != null) {
                    final String cnContent = translations.optString("cn", null);
                    if (cnContent != null && !cnContent.isEmpty()) {
                        final List<LyricsLine> cnLines = LrcParser.parseSynced(cnContent);
                        if (!cnLines.isEmpty()) {
                            final List<LyricsLine> merged = LyricsMerge.mergeRomanization(original, cnLines);
                            if (LyricsMerge.hasText(merged)) {
                                result.put("zh", merged);
                            }
                        }
                    }
                }
            }
        }

        return result.isEmpty() ? null : result;
    }

    @Nullable
    private List<String> extractSongwriters(JSONObject response) {
        final List<String> songwriters = new ArrayList<>();

        JSONObject track = response.optJSONObject("track");
        if (track == null) {
            final JSONObject seoTrack = response.optJSONObject("seo_track");
            if (seoTrack != null) {
                track = seoTrack.optJSONObject("track");
            }
        }
        if (track == null) return null;

        final JSONObject songMakerTeam = track.optJSONObject("song_maker_team");
        if (songMakerTeam == null) return null;

        final JSONArray composers = songMakerTeam.optJSONArray("composers");
        if (composers != null) {
            for (int i = 0; i < composers.length(); i++) {
                final JSONObject composer = composers.optJSONObject(i);
                if (composer != null) {
                    final String name = composer.optString("name", null);
                    if (name != null && !name.isEmpty()) {
                        songwriters.add(name);
                    }
                }
            }
        }

        final JSONArray lyricists = songMakerTeam.optJSONArray("lyricists");
        if (lyricists != null) {
            for (int i = 0; i < lyricists.length(); i++) {
                final JSONObject lyricist = lyricists.optJSONObject(i);
                if (lyricist != null) {
                    final String name = lyricist.optString("name", null);
                    if (name != null && !name.isEmpty()) {
                        songwriters.add(name);
                    }
                }
            }
        }

        return songwriters.isEmpty() ? null : songwriters;
    }

    @Nullable
    private static HttpURLConnection openConnection(String url, String userAgent) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "*/*");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            return connection;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String generateClientId() {
        final Random random = ThreadLocalRandom.current();
        return String.valueOf(random.nextLong(10_000_000, 99_999_999))
                + random.nextLong(10_000_000, 99_999_999);
    }
}
