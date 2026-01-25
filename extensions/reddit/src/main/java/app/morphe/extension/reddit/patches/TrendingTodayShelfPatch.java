package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class TrendingTodayShelfPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static boolean hideTrendingTodayShelf() {
        return Settings.HIDE_TRENDING_TODAY_SHELF.get();
    }

    public static String removeTrendingLabel(String label) {
        return Settings.HIDE_TRENDING_TODAY_SHELF.get() &&
                label != null &&
                label.startsWith("Trending")
                ? ""
                : label;
    }
}
