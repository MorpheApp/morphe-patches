package app.morphe.extension.shared.patches;

import android.net.Uri;
import android.text.TextUtils;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.privacy.LinkSanitizer;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public final class SanitizeSharingLinksPatch {

    private static final LinkSanitizer sanitizer = new LinkSanitizer(
            "si",
            "is", // New (localized?) tracking parameter.
            "feature" // Old tracking parameter name, and may be obsolete.
    );

    /**
     * Injection point.
     */
    public static String sanitize(String url) {
        if (SharedYouTubeSettings.SANITIZE_SHARING_LINKS.get()) {
            url = sanitizer.sanitizeURLString(url);
        }

        if (SharedYouTubeSettings.REPLACE_MUSIC_LINKS_WITH_YOUTUBE.get()) {
            url = url.replace("music.youtube.com", "youtube.com");
        }

        if (SharedYouTubeSettings.REPLACE_LINKS_WITH_SHORTENER.get()) {
            url = replaceWithShortenedUrl(url);
        }

        return url;
    }

    private static String replaceWithShortenedUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || (!host.equals("youtube.com") && !host.endsWith(".youtube.com"))) {
                return url;
            }
            List<String> segments = uri.getPathSegments();
            if (segments.size() < 2) {
                return url;
            }
            String pathType = segments.get(0);
            if (!"live".equals(pathType) && !"shorts".equals(pathType)) {
                return url;
            }
            String videoId = segments.get(1);
            if (TextUtils.isEmpty(videoId)) {
                return url;
            }
            return new Uri.Builder()
                    .scheme("https")
                    .authority("youtu.be")
                    .appendPath(videoId)
                    .encodedQuery(uri.getEncodedQuery())
                    .encodedFragment(uri.getEncodedFragment())
                    .build()
                    .toString();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceWithShortenedUrl failure: " + url, ex);
            return url;
        }
    }
}
