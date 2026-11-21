/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.applemusicartwork;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Searches the Apple Music catalog (amp-api.music.apple.com) the way the music.apple.com web player does,
 * with the anonymous token of the web player. No third-party server is used.
 * <p>
 * The search result matching is ported from apple-canvas.js found in
 * <a href="https://github.com/Archimetrix/VIVIMUSIC_XTEN">ViviMusic Xten</a> (AGPL-3.0),
 * with permission from the original author to license this Morphe adaptation under plain GPLv3.
 */
final class AppleMusicCatalog {

    private static final String WEB_PLAYER_URL = "https://music.apple.com";
    private static final String CATALOG_API_URL = "https://amp-api.music.apple.com/v1/catalog/";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36";
    private static final String DEFAULT_STOREFRONT = "us";

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 20_000;

    /**
     * How long before it expires the token is renewed.
     */
    private static final long TOKEN_RENEW_MARGIN_MILLISECONDS = 60_000;

    private static final String SEARCH_RESULT_LIMIT = "10";

    /**
     * Lowest score of a search result that can be the played song.
     */
    private static final int MINIMUM_SCORE = 12;

    private static final Pattern WEB_PLAYER_SCRIPT_PATTERN =
            Pattern.compile("/assets/index(?:-legacy)?[~-][a-zA-Z0-9_-]+\\.js");
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("ey[a-zA-Z0-9_-]+\\.ey[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
    private static final Pattern ARTIST_SEPARATOR_PATTERN = Pattern.compile(
            "(?i)\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b");
    private static final Pattern DIACRITIC_PATTERN = Pattern.compile("\\p{Mn}+");
    private static final Pattern NON_ALPHANUMERIC_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern ALBUM_URL_ID_PATTERN = Pattern.compile("/album/(?:[^/?]+/)?(\\d+)(?:[?#]|$)");

    /**
     * Words of compilations, which have no motion artwork of the album of the song.
     */
    private static final String[] COMPILATION_WORDS = {
            "playlist", "set list", "essentials", "dj mix", "mixed", "apple music", "today's hits", "session"
    };

    /**
     * Words that tell apart the versions of a song.
     */
    private static final String[] EDITION_WORDS = {
            "deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus"
    };

    /**
     * Square artworks first, as the player artwork is square.
     */
    private static final String[] MOTION_ARTWORK_KEYS = {
            "motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw", "motionDetailTall"
    };
    private static final String[] MOTION_ARTWORK_URL_KEYS = {"video", "videoUrl", "hlsUrl", "url"};

    @Nullable
    private static String token;
    private static long tokenExpirationMilliseconds;

    private static final class Candidate {
        final JSONObject song;
        final int score;

        Candidate(JSONObject song, int score) {
            this.song = song;
            this.score = score;
        }
    }

    private AppleMusicCatalog() {
    }

    /**
     * Connects to a url of Apple Music.
     *
     * @param range Requested byte range, or null for all of it.
     * @return The connection, after the server answered with a success code.
     */
    static HttpURLConnection connect(String url, @Nullable String token, @Nullable String range) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Origin", WEB_PLAYER_URL);
        connection.setRequestProperty("Referer", WEB_PLAYER_URL + "/");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (range != null) {
            connection.setRequestProperty("Range", "bytes=" + range);
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode / 100 != 2) {
            connection.disconnect();
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && token != null) {
                clearToken(token);
            }
            throw new IOException("HTTP " + responseCode + ": " + url);
        }
        return connection;
    }

    static String getString(String url) throws IOException {
        return Requester.parseStringAndDisconnect(connect(url, null, null));
    }

    /**
     * @return The anonymous token the Apple Music web player uses to read the catalog.
     */
    private static synchronized String getToken() throws IOException {
        final long now = System.currentTimeMillis();
        if (token != null && now < tokenExpirationMilliseconds - TOKEN_RENEW_MARGIN_MILLISECONDS) {
            return token;
        }

        String html = getString(WEB_PLAYER_URL + "/us/browse");
        Set<String> scripts = new LinkedHashSet<>();
        Matcher scriptMatcher = WEB_PLAYER_SCRIPT_PATTERN.matcher(html);
        while (scriptMatcher.find()) {
            scripts.add(scriptMatcher.group());
        }

        for (String script : scripts) {
            String scriptText;
            try {
                scriptText = getString(WEB_PLAYER_URL + script);
            } catch (IOException ex) {
                Logger.printDebug(() -> "Could not load script: " + script, ex);
                continue;
            }

            Matcher tokenMatcher = TOKEN_PATTERN.matcher(scriptText);
            while (tokenMatcher.find()) {
                String candidate = tokenMatcher.group();
                final long expiration = getTokenExpiration(candidate);
                if (expiration > now) {
                    Logger.printDebug(() -> "Found token in: " + script);
                    token = candidate;
                    tokenExpirationMilliseconds = expiration;
                    return candidate;
                }
            }
        }

        throw new IOException("No token found in " + scripts.size() + " scripts");
    }

    /**
     * @return The expiration time of a JSON web token from Apple, or 0 if the text is not one.
     */
    private static long getTokenExpiration(String candidate) {
        try {
            byte[] payload = Base64.decode(candidate.split("\\.")[1],
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            return json.has("iss") ? json.optLong("exp") * 1000 : 0;
        } catch (IllegalArgumentException | JSONException ex) {
            Logger.printDebug(() -> "Not a token: " + candidate, ex);
            return 0;
        }
    }

    private static synchronized void clearToken(String expiredToken) {
        if (expiredToken.equals(token)) {
            token = null;
        }
    }

    private static JSONObject requestCatalog(String path, String... parameters) throws IOException, JSONException {
        StringBuilder url = new StringBuilder(CATALOG_API_URL).append(path).append('?');
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            if (i > 0) url.append('&');
            url.append(parameters[i]).append('=').append(URLEncoder.encode(parameters[i + 1], "UTF-8"));
        }

        HttpURLConnection connection = connect(url.toString(), getToken(), null);
        return new JSONObject(Requester.parseStringAndDisconnect(connection));
    }

    /**
     * @return The storefront (country) of the device, which decides what songs the catalog has.
     */
    private static String getStorefront() {
        String country = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
        return country.length() == 2 ? country : DEFAULT_STOREFRONT;
    }

    /**
     * @return The HLS playlist url of the motion artwork of the album of a song, or null if there is none.
     */
    @Nullable
    static String findMotionArtwork(String title, String artist, @Nullable String album)
            throws IOException, JSONException {
        Set<String> storefronts = new LinkedHashSet<>(Arrays.asList(getStorefront(), DEFAULT_STOREFRONT));
        List<String> albums = album == null
                ? Arrays.asList((String) null)
                : Arrays.asList(album, null);
        Set<String> checkedAlbums = new HashSet<>();

        for (String storefront : storefronts) {
            for (String searchAlbum : albums) {
                String url = findMotionArtwork(storefront, title, artist, searchAlbum, checkedAlbums);
                if (url != null) return url;
            }
        }
        return null;
    }

    @Nullable
    private static String findMotionArtwork(String storefront, String title, String artist,
                                            @Nullable String album, Set<String> checkedAlbums)
            throws IOException, JSONException {
        String titleQuery = toLowerCase(title).contains(toLowerCase(artist)) ? title : artist + " " + title;
        String query = album != null && !toLowerCase(titleQuery).contains(toLowerCase(album))
                ? titleQuery + " " + album
                : titleQuery;

        JSONObject root = requestCatalog(storefront + "/search",
                "term", query,
                "types", "songs",
                "limit", SEARCH_RESULT_LIMIT,
                "extend", "editorialVideo",
                "include", "albums");
        JSONObject results = root.optJSONObject("results");
        JSONObject songs = results == null ? null : results.optJSONObject("songs");
        JSONArray data = songs == null ? null : songs.optJSONArray("data");
        if (data == null) {
            Logger.printDebug(() -> "No search results for: " + storefront + " " + query);
            return null;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0, length = data.length(); i < length; i++) {
            JSONObject song = data.getJSONObject(i);
            JSONObject attributes = song.optJSONObject("attributes");
            if (attributes == null) continue;

            final int score = getScore(attributes, title, artist, album);
            if (score >= MINIMUM_SCORE) {
                candidates.add(new Candidate(song, score));
            }
        }
        candidates.sort((first, second) -> Integer.compare(second.score, first.score));
        Logger.printDebug(() -> "Search " + storefront + " " + query + " found " + data.length()
                + " songs and " + candidates.size() + " candidates");

        for (Candidate candidate : candidates) {
            JSONObject attributes = candidate.song.getJSONObject("attributes");
            String url = getMotionArtworkUrl(attributes.optJSONObject("editorialVideo"));
            if (url != null) return url;

            String albumId = getAlbumId(candidate.song);
            if (albumId != null && checkedAlbums.add(storefront + "/" + albumId)) {
                url = getAlbumMotionArtwork(storefront, albumId);
                if (url != null) return url;
            }
        }
        return null;
    }

    /**
     * @return How well a search result matches the played song, or 0 if it is another artist or a compilation.
     */
    private static int getScore(JSONObject attributes, String title, String artist, @Nullable String album) {
        String name = attributes.optString("name");
        String albumName = attributes.optString("albumName", attributes.optString("collectionName"));
        if (isCompilation(name) || isCompilation(albumName)
                || !artistMatches(artist, attributes.optString("artistName"))) {
            return 0;
        }

        int score = 10;

        String normalizedTitle = normalize(title);
        String normalizedName = normalize(name);
        if (normalizedName.equals(normalizedTitle)) {
            score += 15;
        } else if (normalizedName.contains(normalizedTitle) || normalizedTitle.contains(normalizedName)) {
            score += 7;
        } else {
            score -= 10;
        }

        String lowercaseTitle = toLowerCase(title);
        String lowercaseName = toLowerCase(name);
        for (String word : EDITION_WORDS) {
            final boolean inTitle = lowercaseTitle.contains(word);
            final boolean inName = lowercaseName.contains(word);
            if (inTitle && inName) {
                score += 5;
            } else if (inName) {
                score -= 3;
            }
        }

        if (album != null && !albumName.isEmpty()) {
            String normalizedAlbum = normalize(album);
            String normalizedAlbumName = normalize(albumName);
            if (normalizedAlbumName.equals(normalizedAlbum)) {
                score += 20;
            } else if (normalizedAlbumName.contains(normalizedAlbum) || normalizedAlbum.contains(normalizedAlbumName)) {
                score += 10;
            }
        }

        return score;
    }

    @Nullable
    private static String getAlbumId(JSONObject song) throws JSONException {
        JSONObject relationships = song.optJSONObject("relationships");
        JSONObject albums = relationships == null ? null : relationships.optJSONObject("albums");
        JSONArray data = albums == null ? null : albums.optJSONArray("data");
        if (data != null && data.length() > 0) {
            String id = data.getJSONObject(0).optString("id");
            if (!id.isEmpty()) return id;
        }

        Matcher matcher = ALBUM_URL_ID_PATTERN.matcher(song.getJSONObject("attributes").optString("url"));
        return matcher.find() ? matcher.group(1) : null;
    }

    @Nullable
    private static String getAlbumMotionArtwork(String storefront, String albumId) throws IOException, JSONException {
        // Playlist ids start with "pl.".
        if (albumId.startsWith("pl.")) return null;

        JSONObject root = requestCatalog(storefront + "/albums/" + albumId, "extend", "editorialVideo");
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) return null;

        JSONObject attributes = data.getJSONObject(0).optJSONObject("attributes");
        if (attributes == null || isCompilation(attributes.optString("name"))) return null;

        String url = getMotionArtworkUrl(attributes.optJSONObject("editorialVideo"));
        Logger.printDebug(() -> "Album " + albumId + " motion artwork: " + url);
        return url;
    }

    @Nullable
    private static String getMotionArtworkUrl(@Nullable JSONObject editorialVideo) {
        if (editorialVideo == null) return null;

        for (String artworkKey : MOTION_ARTWORK_KEYS) {
            JSONObject artwork = editorialVideo.optJSONObject(artworkKey);
            if (artwork == null) continue;

            for (String urlKey : MOTION_ARTWORK_URL_KEYS) {
                String url = artwork.optString(urlKey);
                if (!url.isEmpty()) return url;
            }
        }
        return null;
    }

    private static boolean artistMatches(String requested, String found) {
        List<String> requestedArtists = splitArtists(requested);
        List<String> foundArtists = splitArtists(found);
        return !requestedArtists.isEmpty() && !foundArtists.isEmpty()
                && foundArtists.containsAll(requestedArtists);
    }

    private static List<String> splitArtists(String artists) {
        List<String> list = new ArrayList<>();
        for (String artist : ARTIST_SEPARATOR_PATTERN.split(artists)) {
            String normalized = normalize(artist);
            if (!normalized.isEmpty()) {
                list.add(normalized);
            }
        }
        return list;
    }

    private static boolean isCompilation(String name) {
        String lowercaseName = toLowerCase(name);
        for (String word : COMPILATION_WORDS) {
            if (lowercaseName.contains(word)) return true;
        }
        return false;
    }

    /**
     * @return The text in lowercase letters and digits without accents, with single spaces between words.
     */
    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(toLowerCase(text), Normalizer.Form.NFKD);
        String withoutDiacritics = DIACRITIC_PATTERN.matcher(decomposed).replaceAll("");
        return NON_ALPHANUMERIC_PATTERN.matcher(withoutDiacritics).replaceAll(" ").trim();
    }

    private static String toLowerCase(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
