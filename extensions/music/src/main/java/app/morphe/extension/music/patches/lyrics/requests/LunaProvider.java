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

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;
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
        List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<JSONObject> tracks = searchTracks(track);
        if (tracks.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredLyrics> scored = new ArrayList<>();
        for (JSONObject trackObj : tracks) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) break;
            String trackId = trackObj.optString("id", null);
            if (trackId == null || trackId.isEmpty()) {
                continue;
            }
            try {
                Lyrics lyrics = fetchLyricsByTrackId(trackId);
                if (lyrics != null) {
                    int score = LyricsRequests.scoreLyricsCandidate(
                            trackObj.optString("name", ""),
                            firstArtistName(trackObj),
                            trackObj.optLong("duration", 0) / 1000,
                            lyrics, track);
                    scored.add(new ScoredLyrics(score, lyrics));
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch Luna lyrics for a track id", ex);
            }
        }

        scored.sort((a, b) -> b.score - a.score);
        List<Lyrics> results = new ArrayList<>(scored.size());
        for (ScoredLyrics s : scored) {
            results.add(s.lyrics);
        }
        return results;
    }

    private static String firstArtistName(JSONObject trackObj) {
        JSONArray artists = trackObj.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            JSONObject first = artists.optJSONObject(0);
            if (first != null) {
                return first.optString("name", "");
            }
        }
        return "";
    }

    private record ScoredLyrics(int score, Lyrics lyrics) {
    }

    private static int scoreCandidate(JSONObject trackObj, TrackInfo track) {
        String title = trackObj.optString("name", "");
        String artist = "";
        JSONArray artists = trackObj.optJSONArray("artists");
        if (artists != null && artists.length() > 0) {
            JSONObject first = artists.optJSONObject(0);
            if (first != null) {
                artist = first.optString("name", "");
            }
        }
        long durationMs = trackObj.optLong("duration", 0);
        return LyricsRequests.scoreTrackCandidate(title, artist,
                durationMs > 0 ? durationMs / 1000 : 0, track);
    }

    private static boolean isOriginalTrack(JSONObject trackObj) {
        JSONObject label = trackObj.optJSONObject("label_info");
        return label != null && label.optBoolean("is_original", false);
    }

    @Nullable
    private List<JSONObject> searchTracks(TrackInfo track) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        String keyword = track.artist() + " " + track.title();
        String deviceId = generateClientId();
        String installId = generateClientId();

        String url = SEARCH_URL
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
                + "&count=20";

        HttpURLConnection connection = openConnection(url, SEARCH_UA);
        if (connection == null || connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            if (connection != null) {
                LyricsRequests.logFailure("Luna", connection);
            }
            return new ArrayList<>();
        }

        JSONObject response = Requester.parseJSONObject(connection);
        JSONArray resultGroups = response.optJSONArray("result_groups");
        if (resultGroups == null || resultGroups.length() == 0) {
            return new ArrayList<>();
        }

        List<JSONObject> trackList = new ArrayList<>();
        for (int g = 0, length = resultGroups.length(); g < length; g++) {
            JSONObject group = resultGroups.optJSONObject(g);
            if (group == null) continue;
            JSONArray data = group.optJSONArray("data");
            if (data == null) continue;
            for (int d = 0; d < data.length(); d++) {
                JSONObject item = data.optJSONObject(d);
                if (item == null) continue;
                JSONObject meta = item.optJSONObject("meta");
                if (meta == null) continue;
                String itemType = meta.optString("item_type", "");
                if (!"track".equals(itemType)) continue;
                JSONObject entity = item.optJSONObject("entity");
                if (entity == null) continue;
                JSONObject trackObj = entity.optJSONObject("track");
                if (trackObj == null) continue;
                String id = trackObj.optString("id", null);
                if (id != null && !id.isEmpty()) {
                    trackList.add(trackObj);
                }
            }
        }

        trackList.sort((a, b) -> {
            int sa = scoreCandidate(a, track);
            int sb = scoreCandidate(b, track);
            if (isOriginalTrack(a)) sa += 10;
            if (isOriginalTrack(b)) sb += 10;
            return sb - sa;
        });
        return trackList;
    }

    @Nullable
    private Lyrics fetchLyricsByTrackId(String trackId) throws Exception {
        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);

        String url = DETAIL_URL
                + "?track_id=" + LyricsRequests.encode(trackId)
                + "&device_platform=web";

        HttpURLConnection connection = openConnection(url, WEB_UA);
        if (connection == null || connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            if (connection != null) {
                LyricsRequests.logFailure("Luna", connection);
            }
            return null;
        }

        JSONObject response = Requester.parseJSONObject(connection);
        JSONObject lyricInfo = response.optJSONObject("lyric");
        if (lyricInfo == null) {
            return null;
        }

        String content = lyricInfo.optString("content", "");
        if (content.isEmpty()) {
            return null;
        }

        String type = lyricInfo.optString("type", "lrc");

        List<LyricsLine> lines;
        String formatType;

        if ("krc".equals(type)) {
            List<LyricsLine> yrcLines = KrcParser.parse(content);
            if (!yrcLines.isEmpty()) {
                lines = yrcLines;
                formatType = "krc";
            } else {
                lines = LrcParser.parseSynced(content);
                formatType = lines.isEmpty() ? "txt" : "lrc";
            }
        } else {
            List<LyricsLine> lrcLines = LrcParser.parseSynced(content);
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

        Map<String, List<LyricsLine>> translations = parseTranslations(lyricInfo, lines);
        List<String> songwriters = extractSongwriters(response);
        String sourceUrl = "https://www.douyin.com/qishui/song/" + trackId;

        return new Lyrics(lines, name(), !"txt".equals(formatType), null,
                translations != null && !translations.isEmpty() ? translations : null,
                null, songwriters, content, formatType, sourceUrl);
    }

    @Nullable
    private Map<String, List<LyricsLine>> parseTranslations(JSONObject lyricInfo, List<LyricsLine> original) {
        Map<String, List<LyricsLine>> result = new HashMap<>();

        JSONObject langTranslations = lyricInfo.optJSONObject("lang_translations");
        if (langTranslations != null) {
            Iterator<String> keys = langTranslations.keys();
            while (keys.hasNext()) {
                String lang = keys.next();
                JSONObject transObj = langTranslations.optJSONObject(lang);
                if (transObj == null) continue;
                String transContent = transObj.optString("content", "");
                if (transContent.isEmpty()) continue;
                String transType = transObj.optString("type", "lrc");

                List<LyricsLine> transLines;
                if ("krc".equals(transType)) {
                    transLines = KrcParser.parse(transContent);
                } else {
                    transLines = LrcParser.parseSynced(transContent);
                }

                if (!transLines.isEmpty()) {
                    List<LyricsLine> merged = LyricsMerge.mergeRomanization(original, transLines);
                    if (LyricsMerge.hasText(merged)) {
                        result.put(lang, merged);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            String sysLang = Locale.getDefault().getLanguage();
            if ("zh".equals(sysLang)) {
                JSONObject translations = lyricInfo.optJSONObject("translations");
                if (translations != null) {
                    String cnContent = LyricsRequests.optString(translations, "cn");
                    if (cnContent != null && !cnContent.isEmpty()) {
                        List<LyricsLine> cnLines = LrcParser.parseSynced(cnContent);
                        if (!cnLines.isEmpty()) {
                            List<LyricsLine> merged = LyricsMerge.mergeRomanization(original, cnLines);
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
        List<String> songwriters = new ArrayList<>();

        JSONObject track = response.optJSONObject("track");
        if (track == null) {
            JSONObject seoTrack = response.optJSONObject("seo_track");
            if (seoTrack != null) {
                track = seoTrack.optJSONObject("track");
            }
        }
        if (track == null) return null;

        JSONObject songMakerTeam = track.optJSONObject("song_maker_team");
        if (songMakerTeam == null) return null;

        JSONArray composers = songMakerTeam.optJSONArray("composers");
        if (composers != null) {
            for (int i = 0; i < composers.length(); i++) {
                JSONObject composer = composers.optJSONObject(i);
                if (composer != null) {
                    String name = LyricsRequests.optString(composer, "name");
                    if (name != null && !name.isEmpty()) {
                        songwriters.add(name);
                    }
                }
            }
        }

        JSONArray lyricists = songMakerTeam.optJSONArray("lyricists");
        if (lyricists != null) {
            for (int i = 0; i < lyricists.length(); i++) {
                JSONObject lyricist = lyricists.optJSONObject(i);
                if (lyricist != null) {
                    String name = LyricsRequests.optString(lyricist, "name");
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
            HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.valueOf(random.nextLong(10_000_000, 99_999_999))
                + random.nextLong(10_000_000, 99_999_999);
    }
}
