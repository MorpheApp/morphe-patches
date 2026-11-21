/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5044
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HideHomeTabsPatch {
    /**
     * Interface to use obfuscated methods of the home tabs (HomePagerScreenTab).
     */
    public interface HomeTabInterface {
        // Methods are added during patching.
        String patch_getTabId();
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param tabs Tabs of the home feed.
     * @return The tabs that are not hidden. The home feed needs at least one tab,
     *         so all tabs are kept if every tab is hidden.
     */
    public static List<?> hideTabs(List<?> tabs) {
        try {
            if (!Settings.HIDE_FOR_YOU_TAB.get() && !Settings.HIDE_FOLLOWING_TAB.get()
                    && !Settings.HIDE_POPULAR_TAB.get()) {
                return tabs;
            }

            List<Object> shownTabs = new ArrayList<>(tabs.size());
            for (Object tab : tabs) {
                if (!(tab instanceof HomeTabInterface homeTab) || !isHidden(homeTab.patch_getTabId())) {
                    shownTabs.add(tab);
                }
            }

            return shownTabs.isEmpty() ? tabs : shownTabs;
        } catch (Exception ex) {
            Logger.printException(() -> "hideTabs failure", ex);
            return tabs;
        }
    }

    private static boolean isHidden(String tabId) {
        return switch (tabId) {
            // The For You tab, which is also named Discover or Explore.
            case "home" -> Settings.HIDE_FOR_YOU_TAB.get();
            case "following" -> Settings.HIDE_FOLLOWING_TAB.get();
            case "popular" -> Settings.HIDE_POPULAR_TAB.get();
            default -> false;
        };
    }
}
