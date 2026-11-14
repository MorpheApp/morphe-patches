/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4464
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.FilterGroup.FilterGroupResult;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Finds the playlist or album of the open page and of the last opened menu.
 * <p>
 * The Download action of accounts offered Premium is a Premium upsell without the ID.
 * The menu of a page can have no ID of its own, and then the ID of the page is used.
 */
@SuppressWarnings("unused")
public final class PlaylistPageFilter extends Filter {

    private static final int MAX_ID_LENGTH = 64;

    @Nullable
    private static volatile String pagePlaylistId;

    @Nullable
    private static volatile String menuPlaylistId;

    private final StringFilterGroup pageHeader = new StringFilterGroup(
            null,
            "music_page_header.",
            "music_blurred_background_header."
    );

    private final StringFilterGroup menuHeader = new StringFilterGroup(
            null,
            "panel_header."
    );

    private final ByteArrayFilterGroup downloadButton = new ByteArrayFilterGroup(
            null,
            "music_download_button."
    );

    public PlaylistPageFilter() {
        StringFilterGroup menuCarousel = new StringFilterGroup(
                null,
                "tile_button_carousel."
        );

        addIdentifierCallbacks(pageHeader, menuHeader, menuCarousel);
    }

    @Nullable
    public static String getPagePlaylistId() {
        return pagePlaylistId;
    }

    @Nullable
    public static String getMenuPlaylistId() {
        return menuPlaylistId;
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (!DownloadsPatch.isFlyoutMenuOverridden()
                || contentIndex != 0 || Utils.indexOf(path, ".e", identifier.length()) >= 0) {
            return false;
        }

        if (matchedGroup == pageHeader) {
            FilterGroupResult result = downloadButton.check(buffer);
            if (!result.isFiltered()) return false;

            String playlistId = findPlaylistId(buffer,
                    result.getMatchedIndex() + result.getMatchedLength());
            if (playlistId == null || playlistId.startsWith("MPRE")) {
                String fullScanId = findPlaylistId(buffer, 1);
                if (fullScanId != null) {
                    playlistId = fullScanId;
                }
            }
            if (!Objects.equals(playlistId, pagePlaylistId)) {
                final String finalPlaylistId = playlistId;
                Logger.printDebug(() -> "Playlist page: " + finalPlaylistId);
                pagePlaylistId = playlistId;
            }
        } else if (matchedGroup == menuHeader) {
            // Each menu starts with its header, and the carousel with the ID is optional.
            menuPlaylistId = null;
        } else {
            String playlistId = findPlaylistId(buffer, 1);
            if (playlistId != null && !playlistId.equals(menuPlaylistId)) {
                Logger.printDebug(() -> "Playlist menu: " + playlistId);
                menuPlaylistId = playlistId;
            }
        }
        
        return false;
    }

    @Nullable
    private static String findPlaylistId(byte[] buffer, int start) {
        String fallback = null;
        for (int i = Math.max(start, 1), end = buffer.length - 1; i < end; i++) {
            final int length = buffer[i - 1];
            if (length < 2 || length > MAX_ID_LENGTH || i + length > buffer.length) continue;

            final byte first = buffer[i];
            if (first != 'P' && first != 'V' && first != 'O'
                    && first != 'M' && first != 'R' && first != 'L') continue;

            String value = new String(buffer, i, length, StandardCharsets.US_ASCII);
            if (DownloadsPatch.PLAYLIST_ID.matcher(value).matches()) {
                String id = value.startsWith("VL") ? value.substring(2) : value;
                if (!id.startsWith("MPRE")) {
                    return id;
                }
                if (fallback == null) {
                    fallback = id;
                }
            }
        }
        return fallback;
    }
}
