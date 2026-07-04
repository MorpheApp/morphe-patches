/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Umbrella Litho filter for YT Music layout components.
 * <p>
 * New categories (flyout menu items, engagement-panel entries, feed cards, etc.) should be added
 * here as additional identifier/path callbacks + buffer/accessibility gates so the whole family
 * shares one filter registration.
 */
@SuppressWarnings("unused")
public final class LayoutComponentsFilter extends Filter {

    private final StringFilterGroup toggleButtonGroup;
    private final ByteArrayFilterGroup lyricsShareButtonBuffer;
    private final ByteArrayFilterGroup lyricsTranslateButtonBuffer;

    public LayoutComponentsFilter() {
        // region Lyrics engagement panel

        toggleButtonGroup = new StringFilterGroup(
                null,
                "toggle_button.eml"
        );
        addPathCallbacks(toggleButtonGroup);

        lyricsShareButtonBuffer = new ByteArrayFilterGroup(
                Settings.HIDE_LYRICS_SHARE_BUTTON,
                "lyric_share_button"
        );
        lyricsTranslateButtonBuffer = new ByteArrayFilterGroup(
                Settings.HIDE_LYRICS_TRANSLATE_BUTTON,
                "lyric_translate_button"
        );

        // endregion
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
        if (matchedGroup == toggleButtonGroup) {
            // Only hide the specific chip whose marker is present in this component's buffer.
            return lyricsShareButtonBuffer.check(buffer).isFiltered()
                    || lyricsTranslateButtonBuffer.check(buffer).isFiltered();
        }
        return false;
    }
}
