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
 * Finds the playlist or album of the open page, and of the menu that was opened for it.
 */
@SuppressWarnings("unused")
public final class PlaylistPageFilter extends Filter {

    private static final int MAX_ID_LENGTH = 64;

    @Nullable
    private static volatile String pagePlaylistId;

    @Nullable
    private static volatile String menuPlaylistId;

    private final ByteArrayFilterGroup downloadButton = new ByteArrayFilterGroup(
            null,
            "music_download_button."
    );

    public PlaylistPageFilter() {
        addIdentifierCallbacks(
                new StringFilterGroup(null, "music_page_header."),
                new StringFilterGroup(null, "tile_button_carousel.")
        );
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
        try {
            if (!DownloadsPatch.isFlyoutMenuOverridden()
                    || contentIndex != 0 || Utils.indexOf(path, ".e", identifier.length()) >= 0) {
                return false;
            }

            if (identifier.startsWith("music_page_header.")) {
                FilterGroupResult result = downloadButton.check(buffer);
                if (!result.isFiltered()) return false;

                String playlistId = findPlaylistId(buffer,
                        result.getMatchedIndex() + result.getMatchedLength());
                if (!Objects.equals(playlistId, pagePlaylistId)) {
                    Logger.printDebug(() -> "Playlist page: " + playlistId);
                    pagePlaylistId = playlistId;
                }
            } else if (identifier.startsWith("tile_button_carousel.")) {
                String playlistId = findPlaylistId(buffer, 1);
                if (playlistId != null && !playlistId.equals(menuPlaylistId)) {
                    Logger.printDebug(() -> "Playlist menu: " + playlistId);
                    menuPlaylistId = playlistId;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "PlaylistPageFilter failure", ex);
        }
        return false;
    }

    @Nullable
    private static String findPlaylistId(byte[] buffer, int start) {
        for (int i = Math.max(start, 1), end = buffer.length - 1; i < end; i++) {
            final int length = buffer[i - 1];
            if (length < 2 || length > MAX_ID_LENGTH || i + length > buffer.length) continue;

            final byte first = buffer[i];
            if (first != 'P' && first != 'V' && first != 'O'
                    && first != 'M' && first != 'R' && first != 'L') continue;

            String value = new String(buffer, i, length, StandardCharsets.US_ASCII);
            if (DownloadsPatch.PLAYLIST_ID.matcher(value).matches()) return value;
        }
        return null;
    }
}
