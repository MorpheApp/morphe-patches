/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import app.morphe.extension.shared.StringRef;

final class UiText {
    static String get(String key) {
        return key.startsWith("morphe_series_tracker_") ? StringRef.str(key) : key;
    }

    static String format(String key, Object... values) {
        return StringRef.str(key, values);
    }

    static String episodeTitle(TrackerModels.Episode episode) {
        String value = episodeTitleValue(episode);
        return value.equals(episode.title) ? value : get(value);
    }

    static String episodeTitleValue(TrackerModels.Episode episode) {
        if (episode.title.isEmpty()) {
            return episode.available
                    ? "morphe_series_tracker_untitled_episode"
                    : "morphe_series_tracker_unavailable_episode";
        }
        return episode.title;
    }

    private UiText() {}
}
