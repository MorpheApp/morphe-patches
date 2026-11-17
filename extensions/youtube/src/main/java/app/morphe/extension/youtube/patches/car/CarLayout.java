/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import java.util.List;

/**
 * Uses the automotive layout on a car display.
 * <p>
 * The form factor a request is sent with selects the layout the server sends. The automotive form
 * factor breaks some pages, also on actual Android Automotive devices: channel pages and some other
 * browse pages fail, the video description and the Shorts comments do not open, and the navigation
 * buttons gain the deprecated Explore button. Those requests use the tablet form factor, the most
 * similar one, as the Change form factor patch does.
 */
@SuppressWarnings("unused")
public final class CarLayout {
    static final int LARGE_FORM_FACTOR = 2;
    static final int AUTOMOTIVE_FORM_FACTOR = 3;

    /** Pages that use the automotive layout. Every other page is a tablet page. */
    private static final String[] AUTOMOTIVE_BROWSE_IDS = {"FEwhat_to_watch", "FEsubscriptions"};

    private CarLayout() {
    }

    /** Injection point. The form factor of every request, before the per-endpoint hooks. */
    public static int getUniversalFormFactor(int original) {
        return universalFormFactor(original, CarDisplay.isOnCarDisplay());
    }

    /** Injection point. The '/get_watch', '/next', '/guide' and '/reel' requests. */
    public static int replaceBrokenFormFactor(int original) {
        return brokenEndpointFormFactor(original, CarDisplay.isOnCarDisplay());
    }

    /** Injection point. The '/browse' request. */
    public static int replaceBrowseFormFactor(int original, String browseId) {
        return browseFormFactor(original, browseId, CarDisplay.isOnCarDisplay());
    }

    /**
     * Injection point. With the tablet form factor the app sometimes parses an item section of a page
     * that has none and crashes, so such a list is skipped.
     */
    public static boolean checkItemSectionRenderer(List<?> list, int listIndex) {
        return list != null && !list.isEmpty() && listIndex >= 0 && listIndex < list.size();
    }

    static int universalFormFactor(int original, boolean onCarDisplay) {
        return onCarDisplay ? AUTOMOTIVE_FORM_FACTOR : original;
    }

    static int brokenEndpointFormFactor(int original, boolean onCarDisplay) {
        return onCarDisplay && original == AUTOMOTIVE_FORM_FACTOR ? LARGE_FORM_FACTOR : original;
    }

    static int browseFormFactor(int original, String browseId, boolean onCarDisplay) {
        if (!onCarDisplay || original != AUTOMOTIVE_FORM_FACTOR) return original;
        return isAutomotiveBrowseId(browseId) ? AUTOMOTIVE_FORM_FACTOR : LARGE_FORM_FACTOR;
    }

    static boolean isAutomotiveBrowseId(String browseId) {
        if (browseId == null) return true;
        for (String automotive : AUTOMOTIVE_BROWSE_IDS) {
            if (automotive.equals(browseId)) return true;
        }
        return false;
    }
}
