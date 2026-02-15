package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideTrendingTodayShelfPatch {
    private static final boolean HIDE_TRENDING_TODAY_SHELF =
            Settings.HIDE_TRENDING_TODAY_SHELF.get();
    /**
     * 'home_revamp_tab_popular' may be removed or changed at any time,
     * as Reddit frequently changes string keys.
     * Use a hardcoded string as a fallback.
     */
    private static final String TRENDING_LABEL = "Trending";
    private static final String TRENDING_LABEL_LOCALIZED = str("home_revamp_tab_popular");

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean hideTrendingTodayShelf() {
        return HIDE_TRENDING_TODAY_SHELF;
    }

    /**
     * Injection point.
     */
    public static String removeTrendingLabel(String label) {
        if (HIDE_TRENDING_TODAY_SHELF && label != null) {
            if (label.startsWith(TRENDING_LABEL) || label.startsWith(TRENDING_LABEL_LOCALIZED)) {
                return "";
            }
        }

        return label;
    }
}
