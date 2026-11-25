/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

import app.morphe.extension.shared.Logger;

/** Official endpoint unless a valid http(s) override replaces it. */
final class LyricsEndpoints {

    private LyricsEndpoints() {
    }

    // Strip trailing slash so paths append cleanly.
    static String base(String official, @Nullable String override) {
        final String chosen = valid(override) ? override.trim() : official;
        int end = chosen.length();
        while (end > 0 && chosen.charAt(end - 1) == '/') {
            end--;
        }
        return chosen.substring(0, end);
    }

    // Keep one trailing slash for appended paths.
    static String directory(String official, @Nullable String override) {
        return base(official, override) + '/';
    }

    static String[] hosts(String[] official, @Nullable String override) {
        return valid(override) ? new String[]{base("", override)} : official;
    }

    private static boolean valid(@Nullable String value) {
        if (value == null) {
            return false;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            final URI uri = new URI(trimmed);
            final String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            final String host = uri.getHost();
            return host != null && !host.isEmpty();
        } catch (URISyntaxException ex) {
            Logger.printDebug(() -> "Ignoring invalid lyrics endpoint: " + trimmed, ex);
            return false;
        }
    }
}
