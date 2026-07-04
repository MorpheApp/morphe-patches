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
 * Umbrella Litho filter for YT Music layout components hidden via the Morphe patches.
 * <p>
 * New categories (flyout menu items, engagement-panel entries, feed cards, etc.) should be added
 * here as additional path callbacks + buffer/accessibility gates so the whole family shares one
 * filter registration.
 *
 * <h5>Lyrics engagement panel — Share / Translate chip buttons</h5>
 *
 * The buttons' Litho path contains {@code "timed_lyrics_controller"} (the parent controller path).
 * Each button component's serialized proto buffer contains one of the marker strings
 * {@code "lyric_share_button"} or {@code "lyric_translate_button"} — observed from a device log
 * dump of the lyrics panel Litho tree. We register a single broad path callback on the controller
 * so every child component of the lyrics panel enters {@link #isFiltered}, then inspect the buffer
 * to decide whether the specific child is one of the two chip buttons.
 */
@SuppressWarnings("unused")
public final class MusicLayoutComponentsFilter extends Filter {

    private final StringFilterGroup lyricsControllerGroup;
    private final ByteArrayFilterGroup lyricsShareButtonBuffer;
    private final ByteArrayFilterGroup lyricsTranslateButtonBuffer;

    public MusicLayoutComponentsFilter() {
        // region Lyrics engagement panel

        lyricsControllerGroup = new StringFilterGroup(
                null, // Setting-less catch: gating happens via the buffer groups below.
                "timed_lyrics_controller"
        );
        addPathCallbacks(lyricsControllerGroup);

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
        if (matchedGroup == lyricsControllerGroup) {
            // Only hide the specific chip whose marker is present in this component's buffer.
            return lyricsShareButtonBuffer.check(buffer).isFiltered()
                    || lyricsTranslateButtonBuffer.check(buffer).isFiltered();
        }
        return false;
    }
}
