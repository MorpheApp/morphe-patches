/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.patches.lyrics.ui.LyricsPanelInstaller;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Detects the lyrics engagement panel being built.
 *
 * <p>Nothing is hidden here. The filter is only used as a signal, because the
 * timed lyrics component is the earliest reliable indication that the panel the
 * user opened is the lyrics one. Comments being built say the opposite, which is
 * used for panels the app gives no identifier.
 */
@SuppressWarnings("unused")
public final class LyricsPanelFilter extends Filter {

    private final StringFilterGroup comments = new StringFilterGroup(
            null,
            "comment_thread",
            "comment_composer",
            "comment_filter",
            "comments_header",
            "comments_simplebox",
            "community_guidelines",
            "|comment."
    );

    public LyricsPanelFilter() {
        StringFilterGroup timedLyrics = new StringFilterGroup(
                null,
                "timed_lyrics"
        );
        addIdentifierCallbacks(timedLyrics, comments);
        addPathCallbacks(comments);
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
        if (matchedGroup == comments) {
            LyricsPanelInstaller.onOtherPanelDetected();
        } else {
            LyricsPanelInstaller.onLyricsPanelDetected();
        }
        return false;
    }
}
