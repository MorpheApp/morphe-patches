package app.morphe.extension.reddit.patches.nsfw;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Explicitly requested site restriction; no keyword or substring matching. */
public final class BlockedNsfwHosts {
    private BlockedNsfwHosts() {}

    public static boolean isRedgifsHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.equals("redgifs.com") || normalized.endsWith(".redgifs.com");
    }

    public static boolean isRedgifsUrl(String url) {
        try {
            return isRedgifsHost(new URI(url).getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
