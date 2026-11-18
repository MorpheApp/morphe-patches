/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
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
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Interface to use obfuscated methods of the sidebar items
     * (CTAItemUiModel and GenericItemUiModel).
     */
    public interface TitleItemInterface {
        // Methods are added during patching.
        int patch_getTitleResId();
    }

    /**
     * Interface to identify the divider items (DividerItemUiModel).
     */
    public interface DividerItemInterface {
        // Methods are added during patching.
        boolean patch_isDivider();
    }

    /**
     * Injection point.
     * Removes the dividers that hidden items leave behind: a divider at the top of the
     * sidebar, and a divider that directly follows another divider.
     */
    public static List<?> removeExtraDividers(List<?> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }

        List<Object> filtered = new ArrayList<>(list.size());
        boolean previousIsDivider = true; // Treat the top of the sidebar as a divider.
        for (Object item : list) {
            final boolean isDivider = item instanceof DividerItemInterface;
            if (isDivider && previousIsDivider) {
                continue;
            }
            filtered.add(item);
            previousIsDivider = isDivider;
        }

        return filtered.size() == list.size() ? list : filtered;
    }

    /**
     * Injection point.
     */
    public static Collection<?> hideComponents(Collection<?> c, HeaderItemInterface headerItemUiModel) {
        if (c.isEmpty()) {
            return c;
        }

        if (headerItemUiModel != null) {
            String headerItemName = headerItemUiModel.patch_getItemName();
            for (HeaderItem headerItem : HeaderItem.values()) {
                if (headerItem.enabled && headerItem.name().equals(headerItemName)) {
                    return Collections.emptyList();
                }
            }
        } else if (c.size() == 1) {
            // Items without a header (Popular, Latest, Discover communities, Start a community)
            // are added one at a time.
            Object item = c.iterator().next();
            if (item instanceof TitleItemInterface) {
                final int titleResId = ((TitleItemInterface) item).patch_getTitleResId();
                for (SidebarItem sidebarItem : SidebarItem.values()) {
                    if (sidebarItem.enabled && sidebarItem.matches(titleResId)) {
                        return Collections.emptyList();
                    }
                }
            }
        }

        return c;
    }

    private enum SidebarItem {
        POPULAR("popular_feed_label", Settings.HIDE_POPULAR_SHELF),
        LATEST("latest_feed_label", Settings.HIDE_LATEST_SHELF),
        DISCOVER_COMMUNITIES("communities_cta_title", Settings.HIDE_DISCOVER_COMMUNITIES_SHELF),
        START_A_COMMUNITY("label_start_a_community", Settings.HIDE_START_A_COMMUNITY_SHELF);

        private final String titleResName;
        private final boolean enabled;
        private int titleResId = -1;

        SidebarItem(String titleResName, BooleanSetting setting) {
            this.titleResName = titleResName;
            this.enabled = setting.get();
        }

        private boolean matches(int resId) {
            if (titleResId == -1) {
                titleResId = ResourceUtils.getIdentifier(ResourceType.STRING, titleResName);
            }
            return titleResId != 0 && titleResId == resId;
        }
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

        @SuppressWarnings("SameParameterValue")
        HeaderItem(boolean enabled) {
            this.enabled = enabled;
        }

        HeaderItem(BooleanSetting setting) {
            this.enabled = setting.get();
        }
    }
}
