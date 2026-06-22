package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.privacy.LinkSanitizer.replaceWithShortenedURL;
import static app.morphe.extension.shared.privacy.LinkSanitizer.returnSanitizedURLFromURI;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.REPLACE_LINKS_WITH_SHORTENER;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.REPLACE_MUSIC_LINKS_WITH_YOUTUBE;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.SANITIZE_SHARING_LINKS;

import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public final class SanitizeSharingLinksPatch {

    private static final Pattern urlPattern =
            Pattern.compile("https?://\\S+(?<![.!?,-])");
    private static final String googleHostName = "youtube.com";

    /**
     * Injection point.
     */
    public static String sanitize(String originalURL) {
        String url;
        boolean urlChangesApplied = false;

        Matcher urlMatcher = urlPattern.matcher(originalURL);
        if (urlMatcher.find()) {
            url = urlMatcher.group();
        } else {
            return originalURL;
        }

        String host = Uri.parse(url).getHost();
        if (host == null || (!host.endsWith(googleHostName) && !host.equals("youtu.be"))) {
            return originalURL;
        }

        if (SANITIZE_SHARING_LINKS.get()) {
            url = returnSanitizedURLFromURI(url);
            urlChangesApplied = true;
        }

        if (REPLACE_MUSIC_LINKS_WITH_YOUTUBE.get()) {
            url = url.replace("music.youtube.com", googleHostName);
            urlChangesApplied = true;
        }

        if (REPLACE_LINKS_WITH_SHORTENER.get()) {
            url = replaceWithShortenedURL(url);
            urlChangesApplied = true;
        }

        return !urlChangesApplied ? originalURL : url;
    }
}
