/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/**
 * The alpha of the main text, the translation, and the readings where they are not sung yet.
 * Reads a snapshot of the line's look state, so the calculations stay free of any view.
 */
final class LyricsLineAlphas {

    private LyricsLineAlphas() {
    }

    /** The line state the alpha calculations read, captured so no field is read here. */
    record State(boolean dimmed,
                 boolean hasWordTimings,
                 boolean sweepEnabled,
                 boolean allSung,
                 boolean activeLine,
                 boolean lineLookAnimating,
                 boolean lineLookInitialized,
                 boolean wordSyncEnabled,
                 float lookCurAlpha,
                 float lookCurUnsungAlpha,
                 float lookCurTransAlpha,
                 float lookCurReadingAlpha,
                 float lineAlpha,
                 int baseTextAlpha) {
    }

    /** Whether the words are being swept now, or the line is still fading out of it. */
    static boolean hasWordSweep(State s) {
        return s.dimmed() && s.hasWordTimings() && s.sweepEnabled() && !s.allSung()
                && (s.activeLine() || s.lineLookAnimating());
    }

    static float unsungMainAlpha(State s, boolean hasWordSweep) {
        if (s.lineLookInitialized()) {
            return hasWordSweep ? s.lookCurUnsungAlpha() : s.lookCurAlpha();
        }
        final boolean dimMain = s.dimmed() && s.hasWordTimings() && s.sweepEnabled();
        return (dimMain ? LyricsColors.UNSUNG_ALPHA : s.baseTextAlpha() / 255f) * s.lineAlpha();
    }

    static float translationAlpha(State s) {
        if (s.lineLookInitialized()) {
            return s.lookCurTransAlpha();
        }
        return s.lineAlpha() * (s.activeLine()
                ? LyricsColors.ACTIVE_TRANSLATION_ALPHA : LyricsColors.UNSUNG_ALPHA);
    }

    static float unsungReadingAlpha(State s, boolean hasWordSweep) {
        final boolean readingSwept = hasWordSweep && s.wordSyncEnabled();
        if (s.lineLookInitialized()) {
            return readingSwept ? s.lookCurUnsungAlpha() : s.lookCurReadingAlpha();
        }
        if (!s.activeLine()) {
            return s.lineAlpha() * LyricsColors.UNSUNG_ALPHA;
        }
        return s.lineAlpha() * (readingSwept
                ? LyricsColors.UNSUNG_ALPHA : LyricsColors.ACTIVE_READING_ALPHA);
    }
}
