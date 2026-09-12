/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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

public final class AppleMusicProvider implements LyricsProvider {

    private static final String BROWSE_URL = "https://music.apple.com";
    private static final String API_BASE = "https://amp-api.music.apple.com/v1/catalog/";

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
        final String userToken = Settings.APPLE_MUSIC_TOKEN.get();
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

        final String storefront = cachedStorefront != null ? cachedStorefront : "us";
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";

        final String songId = searchSong(track, userToken, storefront);
        if (songId == null) {
            return null;
        }

        return fetchLyrics(userToken, storefront, language, songId);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        final String userToken = Settings.APPLE_MUSIC_TOKEN.get();
        if (userToken.isEmpty()) {
            return new ArrayList<>();
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
                return new ArrayList<>();
            }
            if (cachedStorefront == null) {
                resolveStorefront(userToken);
            }
        }

        final String storefront = cachedStorefront != null ? cachedStorefront : "us";
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";

        final List<String> songIds = searchAllSongs(track, userToken, storefront);
        if (songIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Lyrics> results = new ArrayList<>();
        for (String songId : songIds) {
            if (results.size() >= 5) {
                break;
            }
            try {
                Lyrics lyrics = fetchLyrics(userToken, storefront, language, songId);
                if (lyrics != null) {
                    results.add(lyrics);
                }
            } catch (Exception ex) {
            }
        }
        return results;
    }

    private List<String> searchAllSongs(TrackInfo track, String userToken, String storefront) {
        HttpURLConnection connection = null;
        try {
            final String term = LyricsRequests.encode(track.title() + " " + track.artist());
            final String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return new ArrayList<>();
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONObject results = root.optJSONObject("results");
            if (results == null) {
                return new ArrayList<>();
            }
            final JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                return new ArrayList<>();
            }
            final JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                return new ArrayList<>();
            }

            List<String> ids = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item != null) {
                    final String id = item.optString("id", null);
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            return ids;
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
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
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
            final HttpURLConnection browseConn = LyricsRequests.openConnection(BROWSE_URL);
            browseConn.setRequestProperty("User-Agent", BROWSER_USER_AGENT);
            final int browseCode = browseConn.getResponseCode();
            if (browseCode != 200) {
                return null;
            }
            final String html = LyricsRequests.parseGzipString(browseConn);

            final Matcher jsMatcher = JS_BUNDLE.matcher(html);
            if (jsMatcher.find()) {
                final String jsPath = jsMatcher.group(0);
                final String js = fetchJsBundle(BROWSE_URL + jsPath);
                if (js != null) {
                    final String token = extractJwt(js);
                    if (token != null) {
                        return token;
                    }
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
            final HttpURLConnection jsConn = LyricsRequests.openConnection(jsUrl);
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
        final Matcher m = JWT.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    private void resolveStorefront(String userToken) {
        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/me/storefront", userToken);
            final int code = connection.getResponseCode();
            if (code == 200) {
                final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
                final JSONArray data = root.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    final JSONObject storefront = data.optJSONObject(0);
                    if (storefront != null) {
                        cachedStorefront = LyricsRequests.optString(storefront, "id");
                        final JSONObject attributes = storefront.optJSONObject("attributes");
                        if (attributes != null) {
                            final String lang = LyricsRequests.optString(attributes, "defaultLanguageTag");
                            if (lang != null) {
                                cachedLanguage = lang;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (cachedStorefront == null) {
            final Locale sysLocale = Locale.getDefault();
            final String country = sysLocale.getCountry();
            if (country != null && !country.isEmpty()) {
                cachedStorefront = country.toLowerCase(Locale.ROOT);
            } else {
                cachedStorefront = "us";
            }
            if (cachedLanguage == null) {
                final String lang = sysLocale.getLanguage();
                if (lang != null && !lang.isEmpty()) {
                    cachedLanguage = lang;
                }
            }
        }

        fetchAllSupportedLanguages(userToken);

        final Locale deviceLocale = Locale.getDefault();
        cachedTranslationParam = matchSupportedLanguage(deviceLocale);
    }

    private void fetchAllSupportedLanguages(String userToken) {
        final java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        HttpURLConnection connection = null;
        try {
            connection = openApi("https://amp-api.music.apple.com/v1/storefronts", userToken);
            final int code = connection.getResponseCode();
            if (code == 200) {
                final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
                final JSONArray data = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        final JSONObject sf = data.optJSONObject(i);
                        if (sf == null) continue;
                        final JSONObject attrs = sf.optJSONObject("attributes");
                        if (attrs == null) continue;
                        final JSONArray tags = attrs.optJSONArray("supportedLanguageTags");
                        if (tags == null) continue;
                        for (int j = 0; j < tags.length(); j++) {
                            final String tag = tags.optString(j, null);
                            if (tag != null && !tag.isEmpty()) {
                                merged.add(tag);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
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
        final String lang = locale.getLanguage();
        final String country = locale.getCountry();
        if (lang == null || lang.isEmpty()) {
            return null;
        }
        final String prefix = lang + "-";
        String languageMatch = null;
        for (final String tag : cachedSupportedLanguages) {
            if (tag.startsWith(prefix) || tag.equals(lang)) {
                if (country != null && !country.isEmpty() && tag.contains(country)) {
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
            final String term = LyricsRequests.encode(track.title() + " " + track.artist());
            final String url = API_BASE + storefront + "/search?term=" + term
                    + "&types=songs&limit=5";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONObject results = root.optJSONObject("results");
            if (results == null) {
                return null;
            }
            final JSONObject songs = results.optJSONObject("songs");
            if (songs == null) {
                return null;
            }
            final JSONArray items = songs.optJSONArray("data");
            if (items == null || items.length() == 0) {
                return null;
            }

            final String title = track.title().toLowerCase().trim();
            final String artist = track.artist().toLowerCase().trim();
            String bestId = null;
            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final JSONObject attributes = item.optJSONObject("attributes");
                if (attributes == null) {
                    continue;
                }
                final String itemTitle = attributes.optString("name", "");
                final String itemArtist = attributes.optString("artistName", "");
                final String itemId = item.optString("id", null);
                if (bestId == null) {
                    bestId = itemId;
                }
                if (itemTitle.toLowerCase().contains(title) && itemArtist.toLowerCase().contains(artist)) {
                    bestId = itemId;
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
    private Lyrics fetchLyrics(String userToken, String storefront, String language, String songId) {
        final String sourceUrl = "https://music.apple.com/song/" + songId;

        final Lyrics syllableResult = fetchLyricsSyllable(userToken, storefront, language, songId, sourceUrl);
        final int syllableRank = rankOfLyrics(syllableResult);

        final Lyrics dedicatedResult = fetchLyricsDedicated(userToken, storefront, language, songId, sourceUrl);
        final int dedicatedRank = rankOfLyrics(dedicatedResult);

        final Lyrics includeResult = fetchLyricsInclude(userToken, storefront, language, songId, sourceUrl);
        final int includeRank = rankOfLyrics(includeResult);

        final Lyrics best = syllableRank >= dedicatedRank && syllableRank >= includeRank
                ? syllableResult
                : dedicatedRank >= includeRank ? dedicatedResult : includeResult;
        return best;
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
            final String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            final String url = API_BASE + storefront + "/songs/" + songId
                    + "/syllable-lyrics?l=" + LyricsRequests.encode(translationParam)
                    + "&extend=ttmlLocalizations";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            final JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            final JSONObject attributes = song.optJSONObject("attributes");
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
            final String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            final String url = API_BASE + storefront + "/songs/" + songId
                    + "/lyrics?l=" + LyricsRequests.encode(translationParam) + "&extend=ttmlLocalizations";
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            final JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            final JSONObject attributes = song.optJSONObject("attributes");
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
            final String translationParam = cachedTranslationParam != null
                    ? cachedTranslationParam : language;
            final String url = API_BASE + storefront + "/songs/" + songId
                    + "?include[songs]=albums,lyrics,syllable-lyrics&l=" + LyricsRequests.encode(translationParam);
            connection = openApi(url, userToken);
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            final JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return null;
            }
            final JSONObject song = data.optJSONObject(0);
            if (song == null) {
                return null;
            }
            final JSONObject relationships = song.optJSONObject("relationships");
            if (relationships == null) {
                return null;
            }
            String ttml = null;
            final JSONObject syllableLyrics = relationships.optJSONObject("syllable-lyrics");
            if (syllableLyrics != null) {
                final JSONArray syllableData = syllableLyrics.optJSONArray("data");
                if (syllableData != null && syllableData.length() > 0) {
                    final JSONObject syllable = syllableData.optJSONObject(0);
                    if (syllable != null) {
                        final JSONObject syllableAttrs = syllable.optJSONObject("attributes");
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
                final JSONObject lyrics = relationships.optJSONObject("lyrics");
                if (lyrics != null) {
                    final JSONArray lyricsData = lyrics.optJSONArray("data");
                    if (lyricsData != null && lyricsData.length() > 0) {
                        final JSONObject lyric = lyricsData.optJSONObject(0);
                        if (lyric != null) {
                            final JSONObject lyricAttrs = lyric.optJSONObject("attributes");
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
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        final String language = cachedLanguage != null ? cachedLanguage : "en-US";
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
}
