package app.morphe.extension.reddit.patches;

import android.net.Uri;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class OpenLinksDirectlyPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Parses the given Reddit redirect uri by extracting the redirect query.
     *
     * @param uri The Reddit redirect uri.
     * @return The redirect query.
     */
    public static Uri parseRedirectUri(Uri uri) {
        try {
            if (Settings.OPEN_LINKS_DIRECTLY.get()) {
                final String parsedUri = uri.getQueryParameter("url");
                if (Utils.isNotEmpty(parsedUri)) {
                    return Uri.parse(parsedUri);
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "Can not parse URL: " + uri, e);
        }
        return uri;
    }

}
