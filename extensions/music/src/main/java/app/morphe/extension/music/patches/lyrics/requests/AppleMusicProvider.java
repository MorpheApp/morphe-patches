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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

public final class AppleMusicProvider implements LyricsProvider {

    private static final String BROWSE_URL = "https://music.apple.com";
    private static final String API_BASE = "https://amp-api.music.apple.com/v1/catalog/";
    private static final String LYRICALLY_BASE = "https://lyrics.paxsenix.org";
    private static final String ITUNES_SEARCH = "https://itunes.apple.com/search";

    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern JS_BUNDLE =
            Pattern.compile("/assets/index~[^/\"'\\s]+\\.js");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+");

    private static final Object TOKEN_LOCK = new Object();
    @Nullable
    private static String cachedDevToken;
    @Nullable
    private static String cachedStorefront;
    @Nullable
    private static String cachedLanguage;
    @Nullable
    private static String cachedTranslationParam;
    @Nullable
    private static String[] cachedSupportedLanguages;

    private record ResolvedContext(String userToken, String storefront, String language) {}

    @Nullable
    private ResolvedContext resolveContext() {
        String userToken = Settings.APPLE_MUSIC_TOKEN.get();
        if (userToken.isEmpty()) {
            return null;
        }

        synchronized (TOKEN_LOCK) {
            if (cachedDevToken == null) {
                cachedDevToken = fetchDevToken();
                cachedStorefront = null;
                cachedLanguage = null;
                cachedTranslationParam = null;
                cachedSupportedLanguages = null;
            }
            if (cachedDevToken == null) {
                return null;
            }
            if (cachedStorefront == null) {
                resolveStorefront(userToken);
            }
        }

        String storefront = cachedStorefront != null ? cachedStorefront : "us";
        String language = cachedLanguage != null ? cachedLanguage : "en-US";
        return new ResolvedContext(userToken, storefront, language);
    }

    @Override
    public String name() {
        return "Apple";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final ResolvedContext ctx = resolveContext();
        if (ctx == null) {
            return fetchViaLyrically(track);
        }

        String songId = searchSong(track, ctx.userToken, ctx.storefront);
        if (songId == null) {
            return null;
        }

        return fetchLyrics(ctx.userToken, ctx.storefront, ctx.language, songId);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        final ResolvedContext ctx = resolveContext();
        if (ctx == null) {
            final Lyrics single = fetchViaLyrically(track);
            final List<Lyrics> results = new ArrayList<>();
            if (single != null) {
                results.add(single);
            }
            return results;
        }

        List<JSONObject> songs = searchAllSongs(track, ctx.userToken, ctx.storefront);
        if (songs.isEmpty()) {
            return new ArrayList<>();
        }

        List<ScoredLyrics> scored = new ArrayList<>();
        for (JSONObject song : songs) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            String songId = song.optString("id", null);
            if (songId == null) {
                continue;
            }
            try {
                Lyrics lyrics = fetchLyrics(ctx.userToken, ctx.storefront, ctx.language, songId);
                if (lyrics != null) {
                    int score = LyricsRequests.scoreLyricsCandidate(
                            titleFromSong(song), artistFromSong(song),
                            durationFromSong(song), lyrics, track);
                    scored.add(new ScoredLyrics(score, lyrics));
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch Apple Music lyrics for a candidate", ex);
            }
        }

        scored.sort((a, b) -> b.score - a.score);
        List<Lyrics> results = new ArrayList<>(scored.size());
        for (ScoredLyrics s : scored) {
            results.add(s.lyrics);
        }
        return results;
    }

    private static String titleFromSong(JSONObject song) {
        JSONObject attributes = song.optJSONObject("attributes");
        return attributes != null ? attributes.optString("name", "") : "";
    }

    private static String artistFromSong(JSONObject song) {
        JSONObject attributes = song.optJSONObject("attributes");
        return attributes != null ? attributes.optString("artistName", "") : "";
    }

    private static long durationFromSong(JSONObject song) {
        JSONObject attributes = song.optJSONObject("attributes");
        if (attributes == null) return 0;
        long durationMs = attributes.optLong("durationInMillis", 0);
        return durationMs > 0 ? durationMs / 1000 : 0;
    }

    private record ScoredLyrics(int score, Lyrics lyrics) {
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        JSONObject attributes = item.optJSONObject("attributes");
        if (attributes == null) {
            return 0;
        }
        String title = attributes.optString("name", "");
        String artist = attributes.optString("artistName", "");
        long durationMs = attributes.optLong("durationInMillis", 0);
        return LyricsRequests.scoreTrackCandidate(title, artist,
                durationMs > 0 ? durationMs / 1000 : 0, track);
    }

    private List<JSONObject> searchAllSongs(TrackInfo track, String userToken, String storefront) {
        HttpURLConnection connection = null;
        try {
            String term = LyricsRequests.encode(track.title() + " " + track.artist());
            String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return new ArrayList<>();
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONObject results = root.optJSONObject("results");
            if (results == null) {
                return new ArrayList<>();
            }
            JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                return new ArrayList<>();
            }
            JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                return new ArrayList<>();
            }

            List<JSONObject> scored = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    scored.add(item);
                }
            }
            scored.sort((a, b) -> scoreCandidate(b, track) - scoreCandidate(a, track));
            return scored;
        } catch (Exception ex) {
            return new ArrayList<>();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean validateToken(String userToken) {
        synchronized (TOKEN_LOCK) {
            if (cachedDevToken == null) {
                cachedDevToken = fetchDevToken();
            }
        }
        if (cachedDevToken == null) {
            return false;
        }

        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/me/storefront", userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return false;
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONArray data = root.optJSONArray("data");
            return data != null && data.length() > 0;
        } catch (Exception ex) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static String fetchDevToken() {
        try {
            HttpURLConnection browseConn = LyricsRequests.openConnection(BROWSE_URL);
            browseConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            final int browseCode = browseConn.getResponseCode();
            if (browseCode != 200) {
                return null;
            }
            String html = LyricsRequests.parseGzipString(browseConn);

            Matcher jsMatcher = JS_BUNDLE.matcher(html);
            if (jsMatcher.find()) {
                String jsPath = jsMatcher.group(0);
                String js = fetchJsBundle(BROWSE_URL + jsPath);
                if (js != null) {
                    return extractJwt(js);
                }
            }

            return null;
        } catch (IOException ex) {
            return null;
        }
    }

    @Nullable
    private static String fetchJsBundle(String jsUrl) {
        try {
            HttpURLConnection jsConn = LyricsRequests.openConnection(jsUrl);
            jsConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            return LyricsRequests.parseGzipString(jsConn);
        } catch (IOException ex) {
            return null;
        }
    }

    @Nullable
    private static String extractJwt(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = JWT.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    private void resolveStorefront(String userToken) {
        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/me/storefront", userToken);
            final int code = connection.getResponseCode();
            if (code == 200) {
                JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
                JSONArray data = root.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    JSONObject storefront = data.optJSONObject(0);
                    if (storefront != null) {
                        cachedStorefront = LyricsRequests.optString(storefront, "id");
                        JSONObject attributes = storefront.optJSONObject("attributes");
                        if (attributes != null) {
                            String lang = LyricsRequests.optString(attributes, "defaultLanguageTag");
                            if (lang != null) {
                                cachedLanguage = lang;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not resolve the Apple Music language", ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (cachedStorefront == null) {
            Locale sysLocale = Locale.getDefault();
            String country = sysLocale.getCountry();
            if (!country.isEmpty()) {
                cachedStorefront = country.toLowerCase(Locale.ROOT);
            } else {
                cachedStorefront = "us";
            }
            if (cachedLanguage == null) {
                String lang = sysLocale.getLanguage();
                if (!lang.isEmpty()) {
                    cachedLanguage = lang;
                }
            }
        }

        fetchAllSupportedLanguages(userToken);

        Locale deviceLocale = Locale.getDefault();
        cachedTranslationParam = matchSupportedLanguage(deviceLocale);
    }

    private void fetchAllSupportedLanguages(String userToken) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/storefronts", userToken);
            final int code = connection.getResponseCode();
            if (code == 200) {
                JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
                JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject sf = data.optJSONObject(i);
                        if (sf == null) continue;
                        JSONObject attrs = sf.optJSONObject("attributes");
                        if (attrs == null) continue;
                        JSONArray tags = attrs.optJSONArray("supportedLanguageTags");
                        if (tags == null) continue;
                        for (int j = 0; j < tags.length(); j++) {
                            String tag = tags.optString(j, null);
                            if (tag != null && !tag.isEmpty()) {
                                merged.add(tag);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the Apple Music lyrics tags", ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        if (!merged.isEmpty()) {
            cachedSupportedLanguages = merged.toArray(new String[0]);
        }
    }

    @Nullable
    private static String matchSupportedLanguage(Locale locale) {
        if (locale == null || cachedSupportedLanguages == null) {
            return null;
        }
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (lang.isEmpty()) {
            return null;
        }
        String prefix = lang + "-";
        String languageMatch = null;
        for (String tag : cachedSupportedLanguages) {
            if (tag.startsWith(prefix) || tag.equals(lang)) {
                if (!country.isEmpty() && tag.contains(country)) {
                    return tag;
                }
                if (languageMatch == null) {
                    languageMatch = tag;
                }
            }
        }
        return languageMatch;
    }

    @Nullable
    private String searchSong(TrackInfo track, String userToken, String storefront) {
        HttpURLConnection connection = null;
        try {
            String term = LyricsRequests.encode(track.title() + " " + track.artist());
            String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONObject results = root.optJSONObject("results");
            if (results == null) {
                return null;
            }
            JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                return null;
            }
            JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                return null;
            }

            String bestId = null;
            int bestScore = -1;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                int score = scoreCandidate(item, track);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = item.optString("id", null);
                }
            }
            return bestId;
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchLyrics(String userToken, String storefront, String language, String songId) {
        String sourceUrl = "https://music.apple.com/song/" + songId;

        Lyrics syllableResult = fetchLyricsSyllable(userToken, storefront, language, songId, sourceUrl);
        final int syllableRank = rankOfLyrics(syllableResult);

        Lyrics dedicatedResult = fetchLyricsDedicated(userToken, storefront, language, songId, sourceUrl);
        final int dedicatedRank = rankOfLyrics(dedicatedResult);

        Lyrics includeResult = fetchLyricsInclude(userToken, storefront, language, songId, sourceUrl);
        final int includeRank = rankOfLyrics(includeResult);

        return syllableRank >= dedicatedRank && syllableRank >= includeRank
                ? syllableResult
                : dedicatedRank >= includeRank ? dedicatedResult : includeResult;
    }

    private static int rankOfLyrics(@Nullable Lyrics lyrics) {
        if (lyrics == null) {
            return 0;
        }
        int totalWords = 0;
        int linesWithWords = 0;
        for (LyricsLine line : lyrics.lines()) {
            if (line.hasWords()) {
                linesWithWords++;
                totalWords += line.words().size();
            }
        }
        if (totalWords > linesWithWords) {
            return 2;
        }
        return lyrics.synced() ? 1 : 0;
    }

    @Nullable
    private Lyrics fetchLyricsSyllable(String userToken, String storefront, String language,
                                       String songId, String sourceUrl) {
        HttpURLConnection connection = null;
        try {
            String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            String url = API_BASE + storefront + "/songs/" + songId
                    + "/syllable-lyrics?l=" + LyricsRequests.encode(translationParam)
                    + "&extend=ttmlLocalizations";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            JSONObject attributes = song.optJSONObject("attributes");
            if (attributes == null) {
                return null;
            }
            String ttml = LyricsRequests.optString(attributes, "ttmlLocalizations");
            if (ttml == null || ttml.isEmpty()) {
                ttml = LyricsRequests.optString(attributes, "ttml");
            }
            if (ttml == null || ttml.isEmpty()) {
                return null;
            }
            return TtmlParser.ttmlToLyrics(ttml, name(), sourceUrl);
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchLyricsDedicated(String userToken, String storefront, String language,
                                         String songId, String sourceUrl) {
        HttpURLConnection connection = null;
        try {
            String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            String url = API_BASE + storefront + "/songs/" + songId
                    + "/lyrics?l=" + LyricsRequests.encode(translationParam) + "&extend=ttmlLocalizations";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            JSONObject attributes = song.optJSONObject("attributes");
            if (attributes == null) {
                return null;
            }
            String ttml = LyricsRequests.optString(attributes, "ttmlLocalizations");
            if (ttml == null || ttml.isEmpty()) {
                ttml = LyricsRequests.optString(attributes, "ttml");
            }
            if (ttml == null || ttml.isEmpty()) {
                return null;
            }
            return TtmlParser.ttmlToLyrics(ttml, name(), sourceUrl);
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics fetchLyricsInclude(String userToken, String storefront, String language,
                                       String songId, String sourceUrl) {
        HttpURLConnection connection = null;
        try {
            String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            String url = API_BASE + storefront + "/songs/" + songId
                    + "?include[songs]=albums,lyrics,syllable-lyrics&l=" + LyricsRequests.encode(translationParam);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            JSONObject relationships = song.optJSONObject("relationships");
            if (relationships == null) {
                return null;
            }
            String ttml = null;
            JSONObject syllableLyrics = relationships.optJSONObject("syllable-lyrics");
            if (syllableLyrics != null) {
                JSONArray syllableData = syllableLyrics.optJSONArray("data");
                if (syllableData != null && syllableData.length() > 0) {
                    JSONObject syllable = syllableData.optJSONObject(0);
                    if (syllable != null) {
                        JSONObject syllableAttrs = syllable.optJSONObject("attributes");
                        if (syllableAttrs != null) {
                            ttml = LyricsRequests.optString(syllableAttrs, "ttmlLocalizations");
                            if (ttml == null || ttml.isEmpty()) {
                                ttml = LyricsRequests.optString(syllableAttrs, "ttml");
                            }
                        }
                    }
                }
            }
            if (ttml == null || ttml.isEmpty()) {
                JSONObject lyrics = relationships.optJSONObject("lyrics");
                if (lyrics != null) {
                    JSONArray lyricsData = lyrics.optJSONArray("data");
                    if (lyricsData != null && lyricsData.length() > 0) {
                        JSONObject lyric = lyricsData.optJSONObject(0);
                        if (lyric != null) {
                            JSONObject lyricAttrs = lyric.optJSONObject("attributes");
                            if (lyricAttrs != null) {
                                ttml = LyricsRequests.optString(lyricAttrs, "ttmlLocalizations");
                                if (ttml == null || ttml.isEmpty()) {
                                    ttml = LyricsRequests.optString(lyricAttrs, "ttml");
                                }
                            }
                        }
                    }
                }
            }
            if (ttml == null || ttml.isEmpty()) {
                return null;
            }
            return TtmlParser.ttmlToLyrics(ttml, name(), sourceUrl);
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openApi(String url, String userToken) throws IOException {
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        String language = cachedLanguage != null ? cachedLanguage : "en-US";
        connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
        connection.setRequestProperty("Authorization", "Bearer " + cachedDevToken);
        connection.setRequestProperty("media-user-token", userToken);
        connection.setRequestProperty("content-type", "application/json;charset=utf-8");
        connection.setRequestProperty("origin", "https://music.apple.com");
        connection.setRequestProperty("referer", "https://music.apple.com/");
        connection.setRequestProperty("accept", "application/json");
        connection.setRequestProperty("accept-encoding", "gzip, deflate");
        connection.setRequestProperty("accept-language", language + ",en;q=0.9");
        connection.setRequestProperty("Cookie", "media-user-token=" + userToken);
        return connection;
    }

    // ── Lyrically fallback (no Apple Music token required) ──────────────

    @Nullable
    private Lyrics fetchViaLyrically(TrackInfo track) {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }
        try {
            final String trackId = searchItunes(track);
            if (trackId == null) {
                return null;
            }
            final String ttml = fetchLyricly(trackId);
            if (ttml == null) {
                return null;
            }
            final String sourceUrl = "https://music.apple.com/song/" + trackId;
            return TtmlParser.ttmlToLyrics(ttml, "Apple (via Lyrically)", sourceUrl);
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private static String searchItunes(TrackInfo track) {
        HttpURLConnection connection = null;
        try {
            final String term = LyricsRequests.encode(track.title() + " " + track.artist());
            final String url = ITUNES_SEARCH + "?term=" + term + "&entity=song&limit=5";
            connection = LyricsRequests.openConnection(url);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }

            final String title = track.title().toLowerCase().trim();
            final String artist = track.artist().toLowerCase().trim();
            String bestId = null;

            for (int i = 0; i < results.length(); i++) {
                final JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final String itemTitle = item.optString("trackName", "");
                final String itemArtist = item.optString("artistName", "");
                final long itemId = item.optLong("trackId", 0);
                if (itemId == 0) {
                    continue;
                }
                if (bestId == null) {
                    bestId = String.valueOf(itemId);
                }
                if (itemTitle.toLowerCase().contains(title)
                        && itemArtist.toLowerCase().contains(artist)) {
                    bestId = String.valueOf(itemId);
                    break;
                }
            }
            return bestId;
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static String fetchLyricly(String trackId) {
        final String baseUrl = LYRICALLY_BASE
                + "/apple-music/lyrics?id=" + trackId;
        final String result = fetchLyriclyFromUrl(baseUrl);
        if (result != null) {
            return result;
        }
        return fetchLyriclyFromUrl(baseUrl + "&skip_cache=true");
    }

    @Nullable
    private static String fetchLyriclyFromUrl(String url) {
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);

            final String ttmlContent = root.optString("ttmlContent", "");
            if (!ttmlContent.isEmpty()) {
                return ttmlContent;
            }

            final String elrcMulti = root.optString("elrcMultiPerson", "");
            if (!elrcMulti.isEmpty()) {
                return elrcMulti;
            }

            final String elrc = root.optString("elrc", "");
            if (!elrc.isEmpty()) {
                return elrc;
            }

            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
