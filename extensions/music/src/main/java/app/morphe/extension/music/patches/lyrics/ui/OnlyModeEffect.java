/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.List;

/** Ordered side effects of an only-mode transition. */
interface OnlyModeEffect {

    record Save(OnlyModeState.SettingKey key, boolean value) implements OnlyModeEffect {
    }

    record ApplyMode() implements OnlyModeEffect {
    }

    record UpdateDualPill() implements OnlyModeEffect {
    }

    /** Rebuilds the rows; {@code keepRuby} false drops the ruby that rode on a romanize mode. */
    record ShowLyrics(boolean keepRuby) implements OnlyModeEffect {
    }

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

    record CancelNormal(OnlyModeState.Side side) implements OnlyModeEffect {
    }

    record RebuildAfterOnlyEnded() implements OnlyModeEffect {
    }

    static List<OnlyModeEffect> of(OnlyModeEffect... effects) {
        return List.of(effects);
    }
}
