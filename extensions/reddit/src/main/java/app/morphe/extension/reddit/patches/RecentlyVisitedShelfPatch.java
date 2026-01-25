package app.morphe.extension.reddit.patches;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class RecentlyVisitedShelfPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static List<?> hideRecentlyVisitedShelf(List<?> list) {
        return Settings.HIDE_RECENTLY_VISITED_SHELF.get() ? Collections.emptyList() : list;
    }
}
