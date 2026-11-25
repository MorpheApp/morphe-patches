/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.reddit.settings.RedditActivityHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class SettingsInSidebarPatch {
    private static final long SETTINGS_ROW_ID = Long.MIN_VALUE + 0x4d6f72L;
    private static final String SETTINGS_ROW_TITLE = "morphe_settings_title";
    private static final String SETTINGS_ROW_ICON = "morphe_settings_icon_reddit";

    private static int titleResId = -1;
    private static int iconResId = -1;
    @Nullable
    private static volatile Object settingsRow;

    /**
     * Injection point.
     */
    public static int getSettingsRowTitleResId() {
        try {
            if (titleResId == -1) {
                titleResId = ResourceUtils.getIdentifier(ResourceType.STRING, SETTINGS_ROW_TITLE);
            }
            return titleResId;
        } catch (Exception ex) {
            Logger.printException(() -> "getSettingsRowTitleResId failure", ex);
            return 0;
        }
    }

    /**
     * Injection point.
     */
    public static int getSettingsRowIconResId() {
        try {
            if (iconResId == -1) {
                iconResId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, SETTINGS_ROW_ICON);
            }
            return iconResId;
        } catch (Exception ex) {
            Logger.printException(() -> "getSettingsRowIconResId failure", ex);
            return 0;
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static List<?> addSettingsRow(@Nullable List<?> list, @Nullable Object row) {
        try {
            if (list == null || row == null
                    || getSettingsRowTitleResId() == 0
                    || getSettingsRowIconResId() == 0) {
                return list;
            }

            List<Object> updated = new ArrayList<>(list.size() + 1);
            updated.add(row);
            updated.addAll(list);
            settingsRow = row;
            return updated;
        } catch (Exception ex) {
            Logger.printException(() -> "addSettingsRow failure", ex);
            return list;
        }
    }

    /**
     * Injection point.
     */
    public static boolean isSettingsRow(@Nullable Object item) {
        try {
            return item != null && item.equals(settingsRow);
        } catch (Exception ex) {
            Logger.printException(() -> "isSettingsRow failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     */
    public static void openSettings() {
        try {
            RedditActivityHook.showSettingsDialog(Utils.getActivity());
        } catch (Exception ex) {
            Logger.printException(() -> "openSettings failure", ex);
        }
    }
}
