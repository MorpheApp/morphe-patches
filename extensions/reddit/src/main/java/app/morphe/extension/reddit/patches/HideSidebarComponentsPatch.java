package app.morphe.extension.reddit.patches;

import java.util.Collection;
import java.util.Collections;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.settings.BooleanSetting;

@SuppressWarnings("unused")
public final class HideSidebarComponentsPatch {
    /**
     * Interface to use obfuscated methods.
     */
    public interface HeaderItemInterface {
        // Methods are added during patching.
        String patch_getItemName();
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static Collection<?> hideComponents(Collection<?> c, HeaderItemInterface headerItemUiModel) {
        if (headerItemUiModel != null && !c.isEmpty()) {
            String headerItemName = headerItemUiModel.patch_getItemName();
            for (HeaderItem headerItem : HeaderItem.values()) {
                if (headerItem.enabled && headerItem.name().equals(headerItemName)) {
                    return Collections.emptyList();
                }
            }
        }

        return c;
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
