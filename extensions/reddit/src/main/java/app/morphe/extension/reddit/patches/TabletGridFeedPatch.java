/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class TabletGridFeedPatch {

    public enum GridColumns {
        AUTO(0),
        TWO(2),
        THREE(3);

        /**
         * Number of columns, or 0 to choose by the available width.
         */
        final int columns;

        GridColumns(int columns) {
            this.columns = columns;
        }
    }

    /**
     * Grid width needed for 3 columns with {@link GridColumns#AUTO}.
     */
    private static final float AUTO_THREE_COLUMNS_MIN_WIDTH_DP = 840;

    /**
     * Space between the feed top inset and the first row.
     */
    private static final float GRID_TOP_GAP_DP = 8;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private static boolean useGrid() {
        return Settings.TABLET_GRID_FEED.get() && Utils.isTablet();
    }

    /**
     * Injection point.
     *
     * @return If the feed is shown as a grid instead of a list.
     */
    public static boolean showGridFeed(@Nullable Enum<?> feedType) {
        try {
            if (feedType == null || !useGrid()) {
                return false;
            }

            return switch (feedType.name()) {
                case "CUSTOM", "FOLLOWING", "HOME", "LATEST", "NEWS", "POPULAR", "SUBREDDIT" -> true;
                default -> false;
            };
        } catch (Exception ex) {
            Logger.printException(() -> "showGridFeed failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     */
    public static float getGridTopPadding(float topInsetDp) {
        return topInsetDp + GRID_TOP_GAP_DP;
    }

    /**
     * Injection point.
     *
     * @param availableWidth Grid width in pixels.
     * @param originalColumns Number of columns the app uses.
     */
    public static int getGridColumns(int availableWidth, int originalColumns) {
        try {
            if (!useGrid()) {
                return originalColumns;
            }

            final int columns = Settings.TABLET_GRID_COLUMNS.get().columns;
            if (columns != 0) {
                return columns;
            }

            return availableWidth >= Dim.dp(AUTO_THREE_COLUMNS_MIN_WIDTH_DP)
                    ? Math.max(originalColumns, 3)
                    : originalColumns;
        } catch (Exception ex) {
            Logger.printException(() -> "getGridColumns failure", ex);
            return originalColumns;
        }
    }
}
