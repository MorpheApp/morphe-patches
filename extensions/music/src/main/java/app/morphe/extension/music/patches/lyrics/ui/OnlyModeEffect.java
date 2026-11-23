/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.List;

/**
 * Ordered side effects produced by {@link OnlyModeState#dispatch}. The panel is the only place
 * that executes them, so Settings writes and UI refreshes happen in exactly one place and in a
 * deterministic order. Deliberately a plain interface (not sealed) to keep D8/R8 happy on older
 * Android runtimes.
 */
interface OnlyModeEffect {

    /** Persists one of the four only-mode settings. */
    record Save(OnlyModeState.SettingKey key, boolean value) implements OnlyModeEffect {
    }

    /** Re-applies the current mode to the session, active lines and scroll anchor. */
    record ApplyMode() implements OnlyModeEffect {
    }

    /** Mirrors the mode into the dual-mode pill. */
    record UpdateDualPill() implements OnlyModeEffect {
    }

    /** Rebuilds the rows; {@code keepRuby} false drops the ruby that rode on a romanize mode. */
    record ShowLyrics(boolean keepRuby) implements OnlyModeEffect {
    }

    /** Rebuilds keeping the ruby only when it is actually drawn (dual-mode failure fallback). */
    record ShowLyricsKeepRubyIfDrawn() implements OnlyModeEffect {
    }

    record UpdateLabels() implements OnlyModeEffect {
    }

    record UpdateTranslateLabel() implements OnlyModeEffect {
    }

    record UpdateRomanizeLabel() implements OnlyModeEffect {
    }

    record UpdateFooter() implements OnlyModeEffect {
    }

    /** Cancels an in-flight normal (non-only) translation/romanization, including its data. */
    record CancelNormal(OnlyModeState.Side side) implements OnlyModeEffect {
    }

    /** Rebuilds the lines after an only mode ended, and restores both button labels. */
    record RebuildAfterOnlyEnded() implements OnlyModeEffect {
    }

    static List<OnlyModeEffect> of(OnlyModeEffect... effects) {
        return List.of(effects);
    }
}
