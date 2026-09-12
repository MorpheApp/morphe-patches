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

import java.net.HttpURLConnection;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.TrackInfo;

public final class LyricallyAppleMusicProvider implements LyricsProvider {

    private static final String LYRICALLY_BASE = "https://lyrics.paxsenix.org";
    private static final String ITUNES_SEARCH = "https://itunes.apple.com/search";


    @Override
    public String name() {
        return "LyricallyApple";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return null;
        }

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
        HttpURLConnection connection = null;
        try {
            final String url = LYRICALLY_BASE
                    + "/apple-music/lyrics?id=" + trackId;
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            final JSONObject root = LyricsRequests.parseGzipJsonObject(connection);
            if (root == null) {
                return null;
            }

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
