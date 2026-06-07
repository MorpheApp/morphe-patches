package app.morphe.extension.youtube.patches;

import android.net.Uri;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BypassLinkRedirectsPatch {
    private static final String YOUTUBE_REDIRECT_PATH = "/redirect";

    /**
     * Convert the YouTube redirect URI string to the redirect query URI.
     *
     * @param uri The YouTube redirect URI string.
     * @return The redirect query URI.
     */
    public static Uri parseRedirectUri(String uri) {
        final var parsed = Uri.parse(uri);

        if (Settings.BYPASS_LINK_REDIRECTS.get() && Objects.equals(parsed.getPath(), YOUTUBE_REDIRECT_PATH)) {
            var query = Uri.parse(Uri.decode(parsed.getQueryParameter("q")));

            Logger.printDebug(() -> "Bypassing YouTube redirect URI: " + query);

            return query;
        }

        return parsed;
    }
}
