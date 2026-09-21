package app.morphe.extension.youtube.series;

import android.content.Context;

import app.morphe.extension.shared.StringRef;

final class UiText {
    static String get(Context context, String key) {
        return key.startsWith("series_tracker_") ? StringRef.str(key) : key;
    }

    static String format(Context context, String key, Object... values) {
        return StringRef.str(key, values);
    }

    static String episodeTitle(Context context, TrackerModels.Episode episode) {
        String value = episodeTitleValue(episode);
        return value.equals(episode.title) ? value : get(context, value);
    }

    static String episodeTitleValue(TrackerModels.Episode episode) {
        // Older catalogs persisted English fallbacks. Render those in the current locale too.
        if (episode.title.isEmpty()
                || episode.title.equals("Untitled episode")
                || episode.title.equals("Unavailable episode")) {
            return episode.available
                    ? "series_tracker_untitled_episode"
                    : "series_tracker_unavailable_episode";
        }
        return episode.title;
    }

    private UiText() {}
}
