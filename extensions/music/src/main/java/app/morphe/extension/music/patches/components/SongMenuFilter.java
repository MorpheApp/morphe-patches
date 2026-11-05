/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4382
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
 * Finds the song that a menu was opened for.
 * <p>
 * The menu Download item of accounts offered Premium is a Premium upsell action without
 * the video ID, so the ID is taken from the like button of the menu header.
 */
@SuppressWarnings("unused")
public final class SongMenuFilter extends Filter {

    static final int VIDEO_ID_LENGTH = 11;

    /**
     * Song of the last opened menu, or null if the menu is not for a song, such as an album.
     */
    @Nullable
    private static volatile String menuVideoId;

    private final ByteArrayFilterGroup likeButton = new ByteArrayFilterGroup(
            null,
            "like_toggle_button."
    );

    public SongMenuFilter() {
        // Song, album and playlist menus all have this header,
        // so each menu replaces the song of the previous menu.
        addIdentifierCallbacks(
                new StringFilterGroup(
                        null,
                        "panel_header."
                )
        );
    }

    /**
     * @return The video ID of the song of the last opened menu, or null if not a song.
     */
    @Nullable
    public static String getMenuVideoId() {
        return menuVideoId;
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
        try {
            // Nested components of the header have their own buffer.
            if (contentIndex == 0
                    && DownloadsPatch.isFlyoutMenuOverridden()
                    && Utils.indexOf(path, ".e", identifier.length()) < 0) {
                String videoId = findLikeButtonVideoId(buffer);
                if (!Objects.equals(videoId, menuVideoId)) {
                    Logger.printDebug(() -> "Menu opened for song: " + videoId);
                    menuVideoId = videoId;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isFiltered failure", ex);
        }

        return false;
    }

    /**
     * @return The video ID of the like button, or null if the header has no like button.
     */
    @Nullable
    private String findLikeButtonVideoId(byte[] buffer) {
        FilterGroupResult result = likeButton.check(buffer);
        if (!result.isFiltered()) return null;

        // The video ID is the first string field after the button name
        // that is 11 characters long. The byte before a string field is its length.
        final int start = result.getMatchedIndex() + result.getMatchedLength();
        for (int i = start, end = buffer.length - VIDEO_ID_LENGTH; i <= end; i++) {
            if (buffer[i - 1] == VIDEO_ID_LENGTH && isVideoId(buffer, i)) {
                return new String(buffer, i, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
            }
        }
        return null;
    }

    static boolean isVideoId(byte[] buffer, int start) {
        for (int i = start, end = start + VIDEO_ID_LENGTH; i < end; i++) {
            final byte b = buffer[i];
            if (!((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '_' || b == '-')) {
                return false;
            }
        }
        return true;
    }
}
