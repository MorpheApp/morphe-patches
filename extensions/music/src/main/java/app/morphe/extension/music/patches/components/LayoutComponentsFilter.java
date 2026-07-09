/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

@SuppressWarnings("unused")
public final class LayoutComponentsFilter extends Filter {

    private static final String TIMED_LYRICS_IDENTIFIER = "timed_lyrics";
    private static final String TOGGLE_BUTTON_PATH = "toggle_button.e";

    private final StringFilterGroup lyricsShareButton;

    public LayoutComponentsFilter() {
        // Lyrics engagement panel chips. Share is a plain `button.e`; Translate is a
        // `toggle_button.e`. The `identifier` check in isFiltered scopes both callbacks
        // to the timed-lyrics container so unrelated buttons elsewhere are unaffected.
        lyricsShareButton = new StringFilterGroup(
                Settings.HIDE_LYRICS_SHARE_BUTTON,
                "button.e"
        );

        addPathCallbacks(
                lyricsShareButton,
                new StringFilterGroup(Settings.HIDE_LYRICS_TRANSLATE_BUTTON, TOGGLE_BUTTON_PATH)
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
        if (!identifier.contains(TIMED_LYRICS_IDENTIFIER)) {
            return false;
        }

        // `button.e` also matches `toggle_button.e` - let the translate callback own that path.
        return matchedGroup != lyricsShareButton || !path.contains(TOGGLE_BUTTON_PATH);
    }
}
