package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.REPLACE_LINKS_WITH_SHORTENER;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.REPLACE_MUSIC_LINKS_WITH_YOUTUBE;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.SANITIZE_SHARING_LINKS;

import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.privacy.LinkSanitizer;

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
    public static String sanitize(String originalURL) {
        Matcher urlMatcher =
                Pattern.
                compile("https?://[^\\s]+(?<![.!?,-])").
                matcher(originalURL);
        String url;
        if (urlMatcher.find()) {
            url = urlMatcher.group();
        } else {
            return originalURL;
        }

        String host = Uri.parse(url).getHost();
        if (host == null || (!host.equals("youtube.com") && !host.endsWith(".youtube.com"))) {
            return originalURL;
        }

        if (SANITIZE_SHARING_LINKS.get()) {
            url = sanitizer.returnSanitizedURLFromURI(url);
        }

        if (REPLACE_MUSIC_LINKS_WITH_YOUTUBE.get()) {
            url = url.replace("music.youtube.com", "youtube.com");
        }

        if (REPLACE_LINKS_WITH_SHORTENER.get()) {
            url = replaceWithShortenedUrl(url);
        }

        return url;
    }

    private static final String timeQueryTagName = "t";
    private static String replaceWithShortenedUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            List<String> segments = uri.getPathSegments();
            int segmentsSize = segments.size();
            if (segmentsSize == 0) {
                return url;
            }
            String pathType = segments.get(0);
            String videoId = "";
            int currentGetVideoIDAttempts = 0;
            int maxGetVideoIDAttempts = 3;
            while (currentGetVideoIDAttempts <= maxGetVideoIDAttempts) {
                videoId = switch (currentGetVideoIDAttempts) {
                    case 0 -> uri.getQueryParameter("v");
                    case 1 -> uri.getQueryParameter("w");
                    case 2 -> uri.getQueryParameter("s");
                    default -> segments.size() > 1 ? segments.get(1) : "";
                };
                if (!TextUtils.isEmpty(videoId)) {
                    break;
                }
                currentGetVideoIDAttempts++;
            }
            Uri.Builder finalURL =
                    new Uri.Builder()
                    .scheme("https")
                    .authority("youtu.be");
            if (currentGetVideoIDAttempts < maxGetVideoIDAttempts + 1) {
                finalURL.appendPath(videoId);
                String timeQueryContent = uri.getQueryParameter(timeQueryTagName);
                if (!TextUtils.isEmpty(timeQueryContent)) {
                    finalURL.appendQueryParameter(timeQueryTagName, timeQueryContent);
                }
                return finalURL.build().toString();
            }
            return url;
        } catch (Exception ex) {
            Logger.printException(() -> "replaceWithShortenedUrl failure: " + url, ex);
            return url;
        }
    }
}
