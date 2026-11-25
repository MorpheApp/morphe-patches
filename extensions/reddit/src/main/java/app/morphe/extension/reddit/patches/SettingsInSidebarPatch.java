/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.RedditActivityHook;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("unused")
public final class SettingsInSidebarPatch {
    /**
     * Interface to use obfuscated methods of the sidebar presenter (CommunityDrawerPresenter).
     */
    public interface SidebarPresenterInterface {
        // Methods are added during patching.
        void patch_closeSidebar();
    }

    /**
     * Unique id of the row. The ids of the app rows come from a counter.
     */
    private static final long SETTINGS_ROW_ID = Long.MIN_VALUE;

    /**
     * The rows of the app are immutable, so one row can be used for all sidebar lists.
     */
    private static final Object SETTINGS_ROW = createSettingsRow(
            ResourceUtils.getStringIdentifier("morphe_settings_title"),
            ResourceUtils.getDrawableIdentifier("morphe_settings_icon_reddit"),
            SETTINGS_ROW_ID
    );

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Placeholder that is replaced during patching.
     *
     * @return A sidebar row of the app (CTAItemUiModel).
     */
    @SuppressWarnings("SameParameterValue")
    private static Object createSettingsRow(int titleResId, int iconResId, long uniqueId) {
        return null; // Replaced during patching.
    }

    /**
     * Injection point.
     */
    public static List<?> addSettingsRow(List<?> list) {
        try {
            if (Settings.SETTINGS_IN_SIDEBAR.get()) {
                List<Object> updated = new ArrayList<>(list.size() + 1);
                updated.add(SETTINGS_ROW);
                updated.addAll(list);
                return updated;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addSettingsRow failure", ex);
        }

        return list;
    }

    /**
     * Injection point.
     */
    public static boolean isSettingsRow(Object row) {
        return row == SETTINGS_ROW;
    }

    /**
     * Injection point.
     */
    public static void openSettings(SidebarPresenterInterface presenter) {
        try {
            presenter.patch_closeSidebar();
            RedditActivityHook.openSettings();
        } catch (Exception ex) {
            Logger.printException(() -> "openSettings failure", ex);
        }
    }
}
