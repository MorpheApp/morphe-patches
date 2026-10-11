/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3541
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.compose.ui.unit.LayoutDirection;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class FlipPostActionBarPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If the vote buttons are moved into the comment row, next to the comment button.
     */
    public static boolean moveVoteButtons() {
        return Settings.FLIP_POST_ACTION_BAR.get() || Settings.SWAP_POST_VOTE_COMMENT_BUTTONS.get();
    }

    /**
     * Injection point.
     *
     * @param arrangement Horizontal arrangement of the action bar buttons row.
     */
    public static Object getButtonsRowArrangement(Object arrangement) {
        return moveVoteButtons() ? RowArrangement.BUTTONS_ROW : arrangement;
    }

    /**
     * Injection point.
     *
     * @param arrangement Horizontal arrangement of the action bar comment row.
     */
    public static Object getCommentRowArrangement(Object arrangement) {
        return moveVoteButtons() ? RowArrangement.COMMENT_ROW : arrangement;
    }

    /**
     * Horizontal arrangement of the action bar rows.
     * Implements the app's Compose Arrangement.Horizontal interface, added during patching.
     */
    public static final class RowArrangement {
        private static final RowArrangement BUTTONS_ROW = new RowArrangement(false);
        private static final RowArrangement COMMENT_ROW = new RowArrangement(true);

        private final boolean isCommentRow;

        private RowArrangement(boolean isCommentRow) {
            this.isCommentRow = isCommentRow;
        }

        /**
         * Called by the interface arrange method, added during patching.
         */
        public void arrange(int totalSize, int[] sizes, LayoutDirection layoutDirection, int[] outPositions) {
            try {
                if (isCommentRow) {
                    arrangeCommentRow(totalSize, sizes, outPositions);
                } else {
                    arrangeButtonsRow(sizes, outPositions);
                }

                if (layoutDirection == LayoutDirection.Rtl) {
                    // Positions are from the left, so mirror them.
                    for (int i = 0; i < sizes.length; i++) {
                        outPositions[i] = totalSize - outPositions[i] - sizes[i];
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "arrange failure", ex);
            }
        }

        /**
         * Comment row: the vote buttons, then the comment button.
         * At the end of the row when flipped, otherwise at the start.
         */
        private static void arrangeCommentRow(int totalSize, int[] sizes, int[] outPositions) {
            final int count = sizes.length;
            // Same spacing as between the other buttons.
            final int spacing = Dim.dp6;

            int x = 0;
            if (Settings.FLIP_POST_ACTION_BAR.get()) {
                x = totalSize - spacing * (count - 1);
                for (int size : sizes) x -= size;
            }

            final boolean swap = Settings.SWAP_POST_VOTE_COMMENT_BUTTONS.get();
            for (int i = 0; i < count; i++) {
                final int index = swap ? count - 1 - i : i;
                outPositions[index] = x;
                x += sizes[index] + spacing;
            }
        }

        /**
         * Buttons row: a spacer, the comment row, then a spacer and a button
         * for each of the crosspost, share and mod buttons that are shown.
         */
        private static void arrangeButtonsRow(int[] sizes, int[] outPositions) {
            final int count = sizes.length;
            final int[] order = new int[count];

            int index = 0;
            if (Settings.FLIP_POST_ACTION_BAR.get()) {
                // The other buttons, then both spacers, then the comment row.
                for (int i = 3; i < count; i++) order[index++] = i;
                order[index++] = 0;
                if (count > 2) order[index++] = 2;
                order[index] = 1;
            } else {
                // The comment row first, so the vote and comment buttons stay at the start.
                order[index++] = 1;
                for (int i = 0; i < count; i++) {
                    if (i != 1) order[index++] = i;
                }
            }

            int x = 0;
            for (int i : order) {
                outPositions[i] = x;
                x += sizes[i];
            }
        }
    }
}
