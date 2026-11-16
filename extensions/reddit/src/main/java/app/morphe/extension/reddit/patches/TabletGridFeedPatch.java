/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class TabletGridFeedPatch {

    public static final float MIN_TABLET_WIDTH_DP = 600f;
    public static final float THREE_COLUMN_WIDTH_DP = 900f;
    public static final float THREE_COLUMN_GRID_DP = 840f;
    public static final float TOP_GAP_DP = 8f;
    public static final float SIDE_PADDING_DP = 16f;

    public static boolean isPatchIncluded() {
        return false;
    }

    public static boolean useLegacyMasonry() {
        return false;
    }

    public static void callMasonry(
            @Nullable Object state,
            @Nullable Object sink,
            @Nullable Object f1,
            @Nullable Object modifier,
            @Nullable Object padding,
            float spacing,
            @Nullable Object arrangement,
            @Nullable Object footer,
            boolean fullSpan,
            @Nullable Object stateSlot,
            @Nullable Object header,
            @Nullable Object composer,
            int i1,
            int i2
    ) {
    }

    public static void callMasonryLegacy(
            @Nullable Object state,
            @Nullable Object sink,
            @Nullable Object f1,
            @Nullable Object modifier,
            @Nullable Object padding,
            float spacing,
            @Nullable Object arrangement,
            @Nullable Object footer,
            boolean fullSpan1,
            boolean fullSpan2,
            @Nullable Object stateSlot,
            @Nullable Object header,
            @Nullable Object composer,
            int i1,
            int i2
    ) {
    }

    @Nullable
    public static Object newPadding(
            float start,
            float top,
            float end,
            float bottom,
            @Nullable Object placeholder
    ) {
        return null;
    }

    @Nullable
    public static Object footer(@Nullable Object placeholder) {
        return null;
    }

    private static float getScreenWidthDp() {
        try {
            final Context context = Utils.getContext();
            if (context != null) {
                final DisplayManager displayManager =
                        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (displayManager != null) {
                    final Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                    if (display != null) {
                        final DisplayMetrics metrics = new DisplayMetrics();
                        display.getRealMetrics(metrics);
                        if (metrics.density > 0f) {
                            return metrics.widthPixels / metrics.density;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getScreenWidthDp failure", ex);
        }
        return (float) Resources.getSystem().getConfiguration().screenWidthDp;
    }

    public static int columns() {
        final float screenWidthDp = getScreenWidthDp();
        if (screenWidthDp < MIN_TABLET_WIDTH_DP) {
            return 1;
        }
        final String choice = Settings.TABLET_GRID_COLUMNS.get();
        if ("two".equals(choice)) {
            return 2;
        }
        if ("three".equals(choice)) {
            return 3;
        }
        return screenWidthDp < THREE_COLUMN_WIDTH_DP ? 2 : 3;
    }

    public static int columnsForWidth(int availablePx, int original) {
        if (!Settings.TABLET_GRID_FEED.get()) {
            return original;
        }
        try {
            if (getScreenWidthDp() < MIN_TABLET_WIDTH_DP) {
                return original;
            }
            final String choice = Settings.TABLET_GRID_COLUMNS.get();
            if ("two".equals(choice)) {
                return 2;
            }
            if ("three".equals(choice)) {
                return 3;
            }
            final float density = Resources.getSystem().getDisplayMetrics().density;
            final float dp = density > 0f ? (availablePx / density) : (float) availablePx;
            return dp >= THREE_COLUMN_GRID_DP ? Math.max(original, 3) : original;
        } catch (Exception ex) {
            Logger.printException(() -> "columnsForWidth failure", ex);
            return original;
        }
    }

    public static boolean masonryFeed(
            @Nullable Object state,
            @Nullable Object sink,
            @Nullable Object f1,
            @Nullable Object modifier,
            @Nullable Object feedType,
            @Nullable Object header,
            @Nullable Object stateSlot,
            @Nullable Object composer,
            float topInset
    ) {
        if (!Settings.TABLET_GRID_FEED.get()) {
            return false;
        }
        try {
            if (columns() <= 1) {
                return false;
            }
            if (!(feedType instanceof Enum)) {
                return false;
            }
            switch (((Enum<?>) feedType).name()) {
                case "HOME":
                case "POPULAR":
                case "LATEST":
                case "NEWS":
                case "SUBREDDIT":
                case "FOLLOWING":
                case "CUSTOM":
                    break;
                default:
                    return false;
            }
            if (useLegacyMasonry()) {
                callMasonryLegacy(
                        state, sink, f1, modifier,
                        newPadding(SIDE_PADDING_DP, topInset + TOP_GAP_DP, SIDE_PADDING_DP, SIDE_PADDING_DP, null),
                        0f, null, footer(null), true, true, stateSlot, header, composer, 0, 0
                );
            } else {
                callMasonry(
                        state, sink, f1, modifier,
                        newPadding(SIDE_PADDING_DP, topInset + TOP_GAP_DP, SIDE_PADDING_DP, SIDE_PADDING_DP, null),
                        0f, null, footer(null), true, stateSlot, header, composer, 0, 0
                );
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "masonryFeed failure", ex);
            return false;
        }
    }
}
