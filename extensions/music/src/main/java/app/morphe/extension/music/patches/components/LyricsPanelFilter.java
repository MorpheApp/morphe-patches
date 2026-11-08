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
 * user opened is the lyrics one.
 *
 * <p>The old path based check ("|CollectionType|CellType|") was removed. Some songs
 * (for example Turkish tracks) render every lyric line with that path, so the check
 * suppressed real lyrics openings. Background updates are now separated only by
 * {@link LyricsPanelInstaller#isOtherPanelForeground()}.
 */
@SuppressWarnings("unused")
public final class LyricsPanelFilter extends Filter {

    private final StringFilterGroup timedLyricsGroup = new StringFilterGroup(
            null,
            "timed_lyrics"
    );

    private final StringFilterGroup nonLyricsPanelGroup = new StringFilterGroup(
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
        addIdentifierCallbacks(timedLyricsGroup, nonLyricsPanelGroup);
        addPathCallbacks(nonLyricsPanelGroup);
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
        if (matchedGroup == nonLyricsPanelGroup) {
            LyricsPanelInstaller.onNonLyricsPanelDetected();
        } else {
            LyricsPanelInstaller.onLyricsPanelDetected(isBackgroundComponentUpdate());
        }
        return false;
    }

    /**
     * Returns true when Litho is updating an existing timed_lyrics component in the background
     * while another panel (such as comments) is active, rather than building the panel for
     * the first time when opened by the user.
     */
    private static boolean isBackgroundComponentUpdate() {
        return LyricsPanelInstaller.isOtherPanelForeground();
    }
}
