/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4511
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.onthego;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Finds out if the open video player menu has the On-the-go item of the app.
 * Some accounts get a menu without the item, and then the patch adds its own item.
 * Nothing is hidden by this filter.
 */
@SuppressWarnings("unused")
public final class OnTheGoFilter extends Filter {

    /**
     * End of the path of the icon of a menu item.
     */
    private static final String ICON_PATH_END = "|ImageType|";

    /**
     * If the video player menu is open, found by its quality item, which the menu always has.
     */
    private static volatile boolean playerMenuVisible;

    /**
     * If the open video player menu has the On-the-go item of the app.
     */
    private static volatile boolean playerMenuHasOnTheGo;

    private final ByteArrayFilterGroup qualityItem = new ByteArrayFilterGroup(
            null,
            "quality_sheet_header",
            "quick_quality_sheet_content"
    );

    /**
     * Icon of the On-the-go item. Only checked in the icon of an item,
     * as the quality item has a list of icons that also has this icon.
     */
    private final ByteArrayFilterGroup onTheGoIcon = new ByteArrayFilterGroup(
            null,
            "yt_outline_headset_",
            "yt_outline_experimental_headset_"
    );

    /**
     * Premium upsell parameters of the On-the-go item of an account without Premium.
     * 21.x has no icon names in the buffer, but these parameters are only of this item.
     */
    private final ByteArrayFilterGroup onTheGoUpsell = new ByteArrayFilterGroup(
            null,
            "igkCCA8%3D"
    );

    public OnTheGoFilter() {
        addPathCallbacks(new StringFilterGroup(
                null,
                "overflow_menu_item.e"
        ));
    }

    /**
     * @return If the open menu is the video player menu, and it has no On-the-go item of the app.
     */
    static boolean isPlayerMenuWithoutOnTheGo() {
        return playerMenuVisible && !playerMenuHasOnTheGo;
    }

    /**
     * Called when a menu is created, before its items are built.
     */
    static void resetPlayerMenu() {
        playerMenuVisible = false;
        playerMenuHasOnTheGo = false;
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              CharSequence path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        // The menu item is always the start of the path.
        // The Shorts player uses the same menu, but On-the-go is only for videos.
        if (contentIndex != 0
                || ShortsPlayerState.isOpen()
                || !PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return false;
        }

        if (qualityItem.check(buffer).isFiltered()) {
            playerMenuVisible = true;
        }
        if ((Utils.endsWith(path, ICON_PATH_END) && onTheGoIcon.check(buffer).isFiltered())
                || onTheGoUpsell.check(buffer).isFiltered()) {
            playerMenuHasOnTheGo = true;
        }

        return false;
    }
}
