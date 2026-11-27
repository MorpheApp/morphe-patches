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
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/** Full song article text from Wikipedia; no API key needed. */
final class StoryWikipedia {

    private StoryWikipedia() {
    }

    @Nullable
    static String fetch(String artist, String title, String language) {
        String lang = language == null || language.isEmpty() ? "en" : language.toLowerCase(Locale.ROOT);
        // Wikipedia language subdomains are two-letter codes; strip region/script suffixes.
        if (lang.contains("-") || lang.contains("_")) {
            lang = lang.split("[-_]", 2)[0];
        }
        String host = "https://" + lang + ".wikipedia.org/w/api.php";
        String query = title + " " + artist + " song";
        HttpURLConnection conn = null;
        try {
            String url = host + "?action=query&generator=search&gsrsearch="
                    + LyricsRequests.encode(query)
                    + "&gsrlimit=1&prop=extracts&explaintext=1&redirects=1&format=json";
            conn = LyricsRequests.openConnection(url);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            JSONObject root = new JSONObject(Requester.parseStringAndDisconnect(conn));
            JSONObject pages = root.optJSONObject("query") == null
                    ? null : root.optJSONObject("query").optJSONObject("pages");
            if (pages == null) {
                return null;
            }
            JSONArray keys = pages.names();
            if (keys == null || keys.length() == 0) {
                return null;
            }
            JSONObject page = pages.optJSONObject(keys.getString(0));
            if (page == null) {
                return null;
            }
            String extract = page.optString("extract", "").trim();
            return extract.isEmpty() ? null : extract;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Wikipedia story lookup failed", ex);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
