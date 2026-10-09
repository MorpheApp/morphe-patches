/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;

public final class PlaylistInput {
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{2,200}";

    public static String parse(String input) {
        String id = parseOrNull(input);
        if (id == null) throw new IllegalArgumentException("morphe_series_tracker_error_playlist_input");
        return id;
    }

    @Nullable
    static String parseOrNull(String input) {
        String value = input.trim();
        if (value.matches(ID_PATTERN)) return value;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String query = uri.getRawQuery();
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || host == null
                    || query == null
                    || !(host.equals("youtube.com")
                            || host.endsWith(".youtube.com")
                            || host.equals("youtu.be"))) return null;
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && pair[0].equals("list")) {
                    String id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                    if (id.matches(ID_PATTERN)) return id;
                }
            }
        } catch (URISyntaxException | UnsupportedEncodingException | IllegalArgumentException ex) {
            Logger.printDebug(() -> "Not a playlist link: " + value, ex);
        }
        return null;
    }

    public static boolean suggestible(String id) {
        return id != null
                && id.matches(ID_PATTERN)
                && !id.startsWith("RD")
                && !id.startsWith("UL")
                && !id.equals("WL")
                && !id.equals("LL");
    }

    private PlaylistInput() {}
}
