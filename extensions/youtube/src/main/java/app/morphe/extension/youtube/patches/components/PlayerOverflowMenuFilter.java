/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Tells the flyout code that the menu it is about to extend is the player overflow menu,
 * which is the only flyout built from the video being played instead of a list item.
 */
@SuppressWarnings("unused")
public final class PlayerOverflowMenuFilter extends Filter {

    private static volatile boolean menuRendered;

    /**
     * The items render while the menu is being built, which is before the flyout code
     * extends it on the first layout pass.
     */
    public static boolean isMenuRendered() {
        return menuRendered;
    }

    public static void resetMenuRendered() {
        menuRendered = false;
    }

    public PlayerOverflowMenuFilter() {
        addPathCallbacks(new StringFilterGroup(
                null,
                "overflow_menu_item.e"
        ));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        menuRendered = true;
        return false;
    }
}
