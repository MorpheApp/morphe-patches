package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class HideTrendingSearchResultsPatch {

    /**
     * Injection point.
     */
    public static boolean hideTrendingSearchResult(String typingString) {
        return Settings.HIDE_TRENDING_SEARCH_RESULTS.get() && typingString.isEmpty();
    }
}
