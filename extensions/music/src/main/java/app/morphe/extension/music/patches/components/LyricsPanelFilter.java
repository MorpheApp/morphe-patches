/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.patches.lyrics.LyricsPanelInstaller;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Detects the lyrics engagement panel being built.
 *
 * <p>Nothing is hidden here. The filter is only used as a signal, because the
 * timed lyrics component is the earliest reliable indication that the panel the
 * user opened is the lyrics one.
 */
@SuppressWarnings("unused")
public final class LyricsPanelFilter extends Filter {

    public LyricsPanelFilter() {
        addIdentifierCallbacks(new StringFilterGroup(
                Settings.LYRICS_ENABLED,
                "timed_lyrics"
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
        LyricsPanelInstaller.onLyricsPanelDetected();
        return false;
    }
}
