/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class HideTrendingTodayShelfPatch {

    /**
     * 'home_revamp_tab_popular' may be removed or changed at any time,
     * as Reddit frequently changes string keys.
     * Use a hardcoded string as a fallback.
     */
    private static final String TRENDING_LABEL = "Trending";
    private static final String TRENDING_LABEL_KEY = "home_revamp_tab_popular";

    private static volatile String[] trendingLabels = new String[]{ TRENDING_LABEL };

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
        return Settings.HIDE_TRENDING_TODAY_SHELF.get();
    }

    /**
     * Injection point.
     */
    public static boolean shouldHideSearchSectionHeader(Object state) {
        try {
            if (state == null || !hideTrendingTodayShelf()) {
                return false;
            }

            String stateStr = state.toString();
            for (String label : trendingLabels) {
                if (stateStr.contains(label)) return true;
            }

            for (Field field : state.getClass().getDeclaredFields()) {
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(state);
                    if (value != null && Utils.startsWithAny(value, trendingLabels)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "shouldHideSearchSectionHeader failure");
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static void setContentLanguages(List<Locale> locales) {
        try {
            if (trendingLabels == null || trendingLabels.length <= 1) {
                if (Utils.getContext() == null) {
                    Logger.printInfo(() -> "Cannot set content languages, context is null");
                    return;
                }

                Set<String> newTrendingLabels = new HashSet<>(2 * locales.size());
                newTrendingLabels.add(TRENDING_LABEL);

                for (Locale locale : locales) {
                    if (ResourceUtils.getStringIdentifier(TRENDING_LABEL_KEY) != 0) {
                        String localizedTrendingLabel = ResourceUtils.getStringByLocale(TRENDING_LABEL_KEY, locale);
                        if (localizedTrendingLabel != null && !TRENDING_LABEL_KEY.equals(localizedTrendingLabel)) {
                            newTrendingLabels.add(localizedTrendingLabel);
                        }
                    }
                }

                trendingLabels = newTrendingLabels.toArray(new String[0]);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setContentLanguages failure");
        }
    }
}
