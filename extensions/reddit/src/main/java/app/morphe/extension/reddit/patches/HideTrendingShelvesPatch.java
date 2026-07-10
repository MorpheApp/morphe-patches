/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class HideTrendingShelvesPatch {

    public interface TrendingInterface {
        String patch_getTrendingLabel();
    }

    /**
     * 'home_revamp_tab_popular' may be removed or changed at any time,
     * as Reddit frequently changes string keys.
     * Use a hardcoded string as a fallback.
     */
    private static final String TRENDING_LABEL = "Trending";
    private static final String TRENDING_LABEL_KEY = "home_revamp_tab_popular";

    private static volatile String[] trendingLabels = new String[]{ TRENDING_LABEL };

    private static boolean isTrendingSection = false;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hideTrendingShelf() {
        return Settings.HIDE_TRENDING_SHELVES.get();
    }

    /**
     * Injection point.
     */
    public static boolean hideTrendingHeader(TrendingInterface state) {
        try {
            if (state == null) return processTrendingState(null, null);
            return processTrendingState(state.toString(), state.patch_getTrendingLabel());
        } catch (Exception e) {
            Logger.printException(() -> "hideTrendingHeader failure");
            return processTrendingState(null, null);
        }
    }

    /**
     * Injection point.
     */
    public static boolean hideTrendingHeaderLegacy(String headerTitle) {
        try {
            return processTrendingState(headerTitle, null);
        } catch (Exception e) {
            Logger.printException(() -> "hideTrendingHeaderLegacy failure");
            return processTrendingState(null, null);
        }
    }

    /**
     * Centralized logic engine for both Legacy and Modern versions.
     */
    private static boolean processTrendingState(String rawString, String exactLabel) {
        if (!hideTrendingShelf()) {
            isTrendingSection = false;
            return false;
        }

        boolean isTrending = false;

        if (rawString != null) {
            for (String label : trendingLabels) {
                if (rawString.contains(label)) {
                    isTrending = true;
                    break;
                }
            }
        }

        if (!isTrending && exactLabel != null) {
            if (Utils.startsWithAny(exactLabel, trendingLabels)) {
                isTrending = true;
            }
        }

        isTrendingSection = isTrending;
        return isTrending;
    }

    /**
     * Injection point.
     */
    public static boolean hideTrendingCommunitiesShelf() {
        if (!hideTrendingShelf()) return false;

        return isTrendingSection;
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
