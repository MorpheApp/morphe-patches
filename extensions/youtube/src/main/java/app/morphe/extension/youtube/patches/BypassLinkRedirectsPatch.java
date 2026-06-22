package app.morphe.extension.youtube.patches;

import android.net.Uri;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class BypassLinkRedirectsPatch {

    /**
     * Convert the YouTube redirect URI string to the redirect query URI.
     *
     * @param uri The YouTube redirect URI string.
     * @return The redirect query URI.
     */
    public static Uri parseRedirectUri(Uri uri) {
        if (Settings.BYPASS_LINK_REDIRECTS.get() && Objects.equals(uri.getPath(), "/redirect")) {
            var query = Uri.parse(Uri.decode(uri.getQueryParameter("q")));
            Logger.printDebug(() -> "Bypassing YouTube redirect URI: " + query);
            return query;
        }
        return uri;
    }
}
