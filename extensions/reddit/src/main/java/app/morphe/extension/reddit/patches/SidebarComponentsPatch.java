package app.morphe.extension.reddit.patches;

import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("unused")
public final class SidebarComponentsPatch {
    private static final List<?> emptyList = Collections.emptyList();

    public static Collection<?> hideComponents(Collection<?> c, Object headerItemUiModel) {
        if (headerItemUiModel != null && !c.isEmpty()) {
            String headerItemName = getHeaderItemName(headerItemUiModel);
            for (HeaderItem headerItem : HeaderItem.values()) {
                if (headerItem.enabled && headerItem.name().equals(headerItemName)) {
                    return emptyList;
                }
            }
        }

        return c;
    }

    public static Iterable<?> hideRecentlyVisitedDivider(Iterable<?> i) {
        return Settings.HIDE_RECENTLY_VISITED_SHELF.get() ? emptyList : i;
    }

    public static Collection<?> hideRecentlyVisitedShelf(Collection<?> c) {
        return Settings.HIDE_RECENTLY_VISITED_SHELF.get() ? emptyList : c;
    }

    public static Iterable<?> hideRedditProDivider(Iterable<?> i) {
        return Settings.HIDE_REDDIT_PRO_SHELF.get() ? emptyList : i;
    }

    public static Collection<?> hideRedditProShelf(Collection<?> c) {
        return Settings.HIDE_REDDIT_PRO_SHELF.get() ? emptyList : c;
    }

    public static Iterable<?> hideGamesOnRedditDivider(Iterable<?> i) {
        return Settings.HIDE_GAMES_ON_REDDIT_SHELF.get() ? emptyList : i;
    }

    public static Collection<?> hideGamesOnRedditShelf(Collection<?> c) {
        return Settings.HIDE_GAMES_ON_REDDIT_SHELF.get() ? emptyList : c;
    }

    private static String getHeaderItemName(Object headerItemUiModel) {
        // These instructions are ignored by patch.
        Log.i("Extended", "headerItemUiModel: " + headerItemUiModel);
        return "";
    }

    private enum HeaderItem {
        ABOUT(Settings.HIDE_ABOUT_SHELF),
        COMMUNITIES(false),
        COMMUNITY_CLUBS(false),
        COMMUNITY_EVENT(false),
        FAVORITES(false),
        FOLLOWING(false),
        GAMES_ON_REDDIT(Settings.HIDE_GAMES_ON_REDDIT_SHELF),
        MODERATING(false),
        RECENTLY_VISITED(Settings.HIDE_RECENTLY_VISITED_SHELF),
        REDDIT_PRO(Settings.HIDE_REDDIT_PRO_SHELF),
        RESOURCES(Settings.HIDE_RESOURCES_SHELF);

        private final boolean enabled;

        HeaderItem(boolean enabled) {
            this.enabled = enabled;
        }

        HeaderItem(BooleanSetting setting) {
            this.enabled = setting.get();
        }
    }
}
