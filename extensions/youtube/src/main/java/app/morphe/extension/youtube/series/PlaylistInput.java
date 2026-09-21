package app.morphe.extension.youtube.series;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class PlaylistInput {
    public static String parse(String input) {
        String value = input.trim();
        if (value.matches("[A-Za-z0-9_-]{2,200}")) return value;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || host == null
                    || !(host.equals("youtube.com")
                            || host.endsWith(".youtube.com")
                            || host.equals("youtu.be"))) throw new IllegalArgumentException();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && pair[0].equals("list")) {
                    String id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                    if (id.matches("[A-Za-z0-9_-]{2,200}")) return id;
                }
            }
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("series_tracker_error_playlist_input");
    }

    public static boolean suggestible(String id) {
        return id != null
                && id.matches("[A-Za-z0-9_-]{2,200}")
                && !id.startsWith("RD")
                && !id.startsWith("UL")
                && !id.equals("WL")
                && !id.equals("LL");
    }

    private PlaylistInput() {}
}
