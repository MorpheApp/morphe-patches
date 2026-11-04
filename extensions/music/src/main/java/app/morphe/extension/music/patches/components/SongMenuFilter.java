/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Detects the song that a track menu was opened for, and grabs its video ID.
 *
 * The Download menu item of accounts offered Premium is a Premium upsell action that does
 * not include the video ID of the tapped song, so the ID is taken from the menu header,
 * which is rendered when the menu opens and holds the song's like button and ID.
 */
@SuppressWarnings("unused")
public final class SongMenuFilter extends Filter {

    private static final String MENU_HEADER_ROOT_PREFIX = "panel_header.";
    private static final String MENU_ITEM_ROOT_PREFIX = "list_item.";

    private static final byte[] RDAMVM_PREFIX = "RDAMVM".getBytes();

    private final StringFilterGroup menuHeader;
    private final StringFilterGroup menuItem;
    private final ByteArrayFilterGroupList headerGroupList = new ByteArrayFilterGroupList();
    private final ByteArrayFilterGroupList itemGroupList = new ByteArrayFilterGroupList();

    public SongMenuFilter() {
        menuHeader = new StringFilterGroup(
                null,
                MENU_HEADER_ROOT_PREFIX
        );
        menuItem = new StringFilterGroup(
                null,
                MENU_ITEM_ROOT_PREFIX
        );

        headerGroupList.addAll(
                new ByteArrayFilterGroup(
                        null,
                        "like_toggle_button"
                )
        );
        itemGroupList.addAll(
                new ByteArrayFilterGroup(
                        null,
                        "premium_upsell"
                )
        );

        addPathCallbacks(menuHeader, menuItem);
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
        if (matchedGroup == menuHeader) {
            if (!Utils.startsWith(path, MENU_HEADER_ROOT_PREFIX)
                    && !Utils.contains(path, "|" + MENU_HEADER_ROOT_PREFIX)) {
                return false;
            }
            if (!headerGroupList.check(buffer).isFiltered()) {
                return false;
            }

            String videoId = extractVideoId(buffer);
            if (videoId != null) {
                DownloadsPatch.onSongMenuRendered(videoId);
            }
            return false;
        }

        if (matchedGroup == menuItem) {
            if (!Utils.startsWith(path, MENU_ITEM_ROOT_PREFIX)
                    && !Utils.contains(path, "|" + MENU_ITEM_ROOT_PREFIX)) {
                return false;
            }
            if (!itemGroupList.check(buffer).isFiltered()) {
                return false;
            }

            // Some app versions include the song ID in the row itself, so it is
            // grabbed here when available. The row also declares the menu open,
            // which invalidates IDs of previously opened menus.
            String videoId = extractVideoId(buffer);
            if (videoId != null) {
                DownloadsPatch.onSongMenuRendered(videoId);
            }
            DownloadsPatch.onPremiumMenuRowRendered();
        }

        return false;
    }

    /**
     * @return Whether the 11 bytes starting at 'start' are only video ID characters.
     */
    private static boolean isValidVideoId(byte[] bytes, int start) {
        for (int i = 0; i < 11; i++) {
            byte b = bytes[start + i];
            if (!((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '_' || b == '-')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The menu header proto layout references the song ID as a standalone string field,
     * bounded by non printable bytes on both sides.
     *
     * @return The video ID of the song, or null if unknown.
     */
    private static String extractVideoId(byte[] bytes) {
        // 19 = 11 chars video ID + "/vi/" of the thumbnail URL "https://i.ytimg.com/vi/<id>/".
        for (int i = 0; i <= bytes.length - 19; i++) {
            if (bytes[i] == '/' && bytes[i + 1] == 'v' && bytes[i + 2] == 'i' && bytes[i + 3] == '/'
                    && isValidVideoId(bytes, i + 4)) {
                return new String(bytes, i + 4, 11);
            }
        }
        // 17 = 11 chars video ID + "RDAMVM" prefix used by browse IDs of the form "RDAMVM<videoId>".
        for (int i = 0; i <= bytes.length - 17; i++) {
            boolean matches = true;
            for (int j = 0; j < 6; j++) {
                if (bytes[i + j] != RDAMVM_PREFIX[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches && isValidVideoId(bytes, i + 6)) {
                return new String(bytes, i + 6, 11);
            }
        }
        // 13 = 11 chars video ID, delimited by a byte on each side.
        for (int i = 0; i < bytes.length - 13; i++) {
            if (isStringDelimiter(bytes[i]) && isValidVideoId(bytes, i + 1)
                    && isStringDelimiter(bytes[i + 12])) {
                return new String(bytes, i + 1, 11);
            }
        }
        return null;
    }

    private static boolean isStringDelimiter(byte b) {
        return b <= 0x1F;
    }
}
