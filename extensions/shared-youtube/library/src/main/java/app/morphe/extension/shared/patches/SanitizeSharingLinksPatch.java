package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.privacy.LinkSanitizer;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public final class SanitizeSharingLinksPatch {

    private static final LinkSanitizer sanitizer = new LinkSanitizer(
            "si",
            "feature" // Old tracking parameter name, and may be obsolete.
    );

    /**
     * Injection point.
     */
    public static String sanitize(String url) {
        if (SharedYouTubeSettings.SANITIZE_SHARED_LINKS.get()) {
            url = sanitizer.sanitizeUrlString(url);
        }

        if (SharedYouTubeSettings.REPLACE_MUSIC_LINKS_WITH_YOUTUBE.get()) {
            url = url.replace("music.youtube.com", "youtube.com");
        }

        return url;
    }
}
