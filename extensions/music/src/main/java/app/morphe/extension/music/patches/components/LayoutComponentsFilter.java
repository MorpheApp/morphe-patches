/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import android.view.View;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

@SuppressWarnings("unused")
public final class LayoutComponentsFilter extends Filter {

    private static final String TIMED_LYRICS_IDENTIFIER = "timed_lyrics";
    private static final String TOGGLE_BUTTON_PATH = "toggle_button.e";

    private final StringFilterGroup lyricsShareButton;
    private final StringFilterGroup lyricsTranslateButton;

    public LayoutComponentsFilter() {
        final StringFilterGroup exploreShelf = new StringFilterGroup(
                Settings.HIDE_EXPLORE_SHELF,
                "entry_point_button_shelf.e"
        );

        final StringFilterGroup gridShelves = new StringFilterGroup(
                Settings.HIDE_GRID_SHELVES,
                "music_grid_item_carousel.e"
        );

        final StringFilterGroup horizontalShelves = new StringFilterGroup(
                Settings.HIDE_HORIZONTAL_SHELVES,
                "music_horizontal_shelf.e"
        );

        final StringFilterGroup listShelves = new StringFilterGroup(
                Settings.HIDE_LIST_SHELVES,
                "music_list_item_carousel.e"
        );

        // Lyrics engagement panel chips. Share is a plain `button.e`; Translate is a
        // `toggle_button.e`. The `identifier` check in isFiltered scopes both callbacks
        // to the timed-lyrics container so unrelated buttons elsewhere are unaffected.
        lyricsShareButton = new StringFilterGroup(
                Settings.HIDE_LYRICS_SHARE_BUTTON,
                "button.e"
        );

        lyricsTranslateButton = new StringFilterGroup(
                Settings.HIDE_LYRICS_TRANSLATE_BUTTON,
                TOGGLE_BUTTON_PATH
        );

        final StringFilterGroup newFromShelf = new StringFilterGroup(
                Settings.HIDE_NEW_FROM_SHELF,
                "music_action_card_shelf.e"
        );

        final StringFilterGroup playlistShelves = new StringFilterGroup(
                Settings.HIDE_PLAYLIST_SHELVES,
                "music_container_card_shelf.e"
        );

        final StringFilterGroup speedDialShelf = new StringFilterGroup(
                Settings.HIDE_SPEED_DIAL_SHELF,
                "music_speed_dial_shelf.e"
        );

        final StringFilterGroup suggestedForYouShelf = new StringFilterGroup(
                Settings.HIDE_SUGGESTED_FOR_YOU_SHELF,
                "music_shelf_header_wrapper.e",
                "music_list_item_wrapper.e"
        );

        addPathCallbacks(
                exploreShelf,
                gridShelves,
                horizontalShelves,
                listShelves,
                lyricsShareButton,
                lyricsTranslateButton,
                newFromShelf,
                playlistShelves,
                speedDialShelf,
                suggestedForYouShelf
        );
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
        if (matchedGroup == lyricsShareButton || matchedGroup == lyricsTranslateButton) {
            if (!identifier.contains(TIMED_LYRICS_IDENTIFIER)) {
                return false;
            }

            if (matchedGroup == lyricsShareButton) {
                // `button.e` also matches `toggle_button.e` - let the translate callback own that path.
                return !path.contains(TOGGLE_BUTTON_PATH);
            }

            return true;
        }

        return true;
    }

    /**
     * Injection point.
     */
    public static void hideAudioVideoToggle(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_AUDIO_VIDEO_TOGGLE, view);
    }

    /**
     * Injection point.
     */
    public static void hideRepeatButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_REPEAT_BUTTON, view);
    }

    /**
     * Injection point.
     */
    public static void hideShuffleButton(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_SHUFFLE_BUTTON, view);
    }
}
