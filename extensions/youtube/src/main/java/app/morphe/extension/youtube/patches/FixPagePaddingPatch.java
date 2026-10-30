/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class FixPagePaddingPatch {

    private static volatile boolean keyboardMarginRemoved;

    public static boolean isKeyboardMarginRemoved() {
        return keyboardMarginRemoved;
    }

    /**
     * Injection point.
     * <p>
     * Sets the bottom margin of the layout that holds the page, the player and the navigation bar
     * while the keyboard is open and a text field is focused.
     * With edge-to-edge / translucent navigation bar flags enabled, the app calculates the keyboard
     * height and sets it as the bottom margin of the watch while coordinator layout. This shrinks
     * the layout and moves the bottom navigation bar up on top of the soft keyboard.
     * Replacing the keyboard height with no margin (0) keeps the navigation bar at the bottom
     * behind the keyboard, except while the legacy miniplayer is shown.
     *
     * @param original The keyboard height the app computed for the margin.
     * @return 0 to keep the layout at the bottom, or the keyboard height if legacy miniplayer is active.
     */
    public static int getKeyboardMargin(int original) {
        try {
            if (PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED
                    && !MiniplayerPatch.getCurrentMiniplayerType().isModern()
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Activity activity = Utils.getActivity();
                WindowInsets insets = activity == null
                        ? null
                        : activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    return Math.max(original, insets.getInsets(WindowInsets.Type.ime()).bottom);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getKeyboardMargin failure", ex);
        }

        keyboardMarginRemoved = true;
        return 0;
    }

    /**
     * Injection point.
     * <p>
     * When a feed or browse list is laid out behind the navigation bar or window insets are applied,
     * the bottom padding of the list holds the bottom window inset. With the soft keyboard open,
     * that inset holds the keyboard too, so the list padding grows by the keyboard height.
     * At the same time, the page layout margin may also account for the keyboard, or the list
     * cannot scroll, causing the top items (such as the watch history search bar and shelf)
     * to be squished and hidden. The keyboard height is removed from the bottom padding.
     *
     * @param view The RecyclerView of the feed/browse list.
     * @param bottomPadding The computed bottom padding before adjustment.
     * @return The adjusted bottom padding with keyboard height subtracted if applicable.
     */
    public static int getAdjustedFeedListBottomPadding(View view, int bottomPadding) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return bottomPadding;
        }

        try {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) {
                int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                if (bottomPadding >= imeBottom && imeBottom > 0) {
                    int systemBarsBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                    int keyboardHeight = imeBottom - systemBarsBottom;
                    if (keyboardHeight > 0) {
                        return bottomPadding - keyboardHeight;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getAdjustedFeedListBottomPadding failure", ex);
        }

        return bottomPadding;
    }
}
