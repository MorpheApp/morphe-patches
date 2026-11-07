/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.settings.Settings;

/** Tests for the static display helpers of {@link LyricsPresentation}. The class holds mutable static state, so every test resets the cycle and panel mode and pins the settings first. */
public class LyricsPresentationTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        LyricsPresentation.resetTrackCycle();
        LyricsPresentation.setMainLineOnlyState(false, false, null, null);
        // Pin the settings these tests touch so shared JVM state from other test classes cannot leak in.
        Settings.LYRICS_FORCE_CAPITALIZE.save(false);
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(false);
        Settings.LYRICS_HAN_FORM.save(false);
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("DEFAULT");
        Settings.LYRICS_MINI_TRACK_FORMAT.save(MiniLyricsFormatter.DEFAULT_TRACK_FORMAT);
        Settings.LYRICS_MINI_ENCAPSULATION.save(MiniLyricsFormatter.DEFAULT_ENCAPSULATION);
        Settings.LYRICS_MINI_CHAR_LIMIT.save(0);
        Settings.LYRICS_MINI_NEXT_OFFSET_MS.save(-800);
        Settings.LYRICS_MINI_INSTRUMENTAL.save(MiniLyricsFormatter.DEFAULT_INSTRUMENTAL);
    }

    // ── isFormattingEnabled ──

    @Test
    public void isFormattingDisabledByDefault() {
        assertFalse(LyricsPresentation.isFormattingEnabled());
    }

    @Test
    public void isFormattingEnabledWhenCapitalizeOn() {
        Settings.LYRICS_FORCE_CAPITALIZE.save(true);
        assertTrue(LyricsPresentation.isFormattingEnabled());
    }

    @Test
    public void isFormattingEnabledWhenStripOn() {
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(true);
        assertTrue(LyricsPresentation.isFormattingEnabled());
    }

    @Test
    public void isFormattingEnabledWhenHanFormOn() {
        Settings.LYRICS_HAN_FORM.save(true);
        assertTrue(LyricsPresentation.isFormattingEnabled());
    }

    @Test
    public void isFormattingDisabledAfterTurningAllOff() {
        Settings.LYRICS_FORCE_CAPITALIZE.save(true);
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(true);
        Settings.LYRICS_HAN_FORM.save(true);
        Settings.LYRICS_FORCE_CAPITALIZE.save(false);
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(false);
        Settings.LYRICS_HAN_FORM.save(false);
        assertFalse(LyricsPresentation.isFormattingEnabled());
    }

    // ── formatText ──

    @Test
    public void formatTextReturnsRawWhenFormattingDisabled() {
        assertEquals("hello.", LyricsPresentation.formatText("hello."));
        assertEquals("", LyricsPresentation.formatText(""));
    }

    @Test
    public void formatTextCapitalizesWhenEnabled() {
        Settings.LYRICS_FORCE_CAPITALIZE.save(true);
        assertEquals("Hello.", LyricsPresentation.formatText("hello."));
    }

    @Test
    public void formatTextStripsTrailingWhenEnabled() {
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(true);
        assertEquals("hello", LyricsPresentation.formatText("hello."));
    }

    // ── format (delegation) ──

    @Test
    public void formatDelegatesToFormatterWithDefaults() {
        LyricsTextFormatter.Result result = LyricsPresentation.format("hello.");
        assertEquals("hello.", result.text());
    }

    @Test
    public void formatDelegatesWithCapitalizeAndStrip() {
        Settings.LYRICS_FORCE_CAPITALIZE.save(true);
        Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.save(true);
        LyricsTextFormatter.Result result = LyricsPresentation.format("hello.");
        assertEquals("Hello", result.text());
    }

    @Test
    public void formatWithHanModePassesThrough() {
        // Under the mockable jar the ICU Transliterator is null, so Han chars pass through.
        Settings.LYRICS_HAN_FORM.save(true);
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("zh-Hans");
        LyricsTextFormatter.Result result = LyricsPresentation.format("你好");
        assertEquals("你好", result.text());
        assertEquals(0, result.offset(0));
        assertEquals(1, result.offset(1));
        assertEquals(2, result.offset(2));
    }

    @Test
    public void formatWithHanTraditionalModePassesThrough() {
        Settings.LYRICS_HAN_FORM.save(true);
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("zh-Hant");
        LyricsTextFormatter.Result result = LyricsPresentation.format("你好");
        assertEquals("你好", result.text());
    }

    // ── trackLabel / resetTrackCycle ──

    @Test
    public void trackLabelHidesVariantSegmentWithDefaultFormat() {
        // No version means the {v} segment is hidden, leaving "Title - Artist".
        assertEquals("Song - Artist", LyricsPresentation.trackLabel("Song", "Artist"));
    }

    @Test
    public void trackLabelMultiLineFormatReturnsFirstFrame() {
        Settings.LYRICS_MINI_TRACK_FORMAT.save("{t}\n{a}");
        assertEquals("Song", LyricsPresentation.trackLabel("Song", "Artist"));
    }

    @Test
    public void trackLabelReturnsFirstFrameAfterReset() {
        Settings.LYRICS_MINI_TRACK_FORMAT.save("{t}\n{a}");
        LyricsPresentation.resetTrackCycle();
        assertEquals("Song", LyricsPresentation.trackLabel("Song", "Artist"));
    }

    @Test
    public void trackLabelDifferentTrackReturnsFirstFrame() {
        Settings.LYRICS_MINI_TRACK_FORMAT.save("{t}\n{a}");
        assertEquals("Song", LyricsPresentation.trackLabel("Song", "Artist"));
        // A different track produces different frames, so the cycle resets to the first.
        assertEquals("Other", LyricsPresentation.trackLabel("Other", "Artist"));
    }

    // ── miniLine ──

    @Test
    public void miniLineReturnsVocalText() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, 5000, "hello world", List.of()));
        // selectionPosition = 1000 + 800 = 1800, inside the vocal; hidden :(x{n}): leaves a trailing space.
        assertEquals("hello world ", LyricsPresentation.miniLine(lyrics, 10000, 1000, "Song", "Artist"));
    }

    @Test
    public void miniLineKeepsPreviousLineAfterVocalEnds() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, 2000, "hello", List.of()));
        // Past the vocal end, the previous line is kept; encapsulate leaves a trailing space.
        assertEquals("hello ", LyricsPresentation.miniLine(lyrics, 10000, 5000, "Song", "Artist"));
    }

    @Test
    public void miniLineReturnsInstrumentalDuringGap() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, 2000, "hello", List.of()),
                new LyricsLine(5000, 7000, "world", List.of()));
        // Gap is [2000, 5000). Position 3000 → selectionPosition 3800 → instrumental frame 1.
        assertEquals("♪♪", LyricsPresentation.miniLine(lyrics, 10000, 3000, "Song", "Artist"));
    }

    @Test
    public void miniLineReturnsInstrumentalBeforeFirstLine() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(5000, 7000, "hello", List.of()));
        // Gap is [0, 5000). Position 0 → selectionPosition 800 → instrumental frame 0.
        assertEquals("♪", LyricsPresentation.miniLine(lyrics, 10000, 0, "Song", "Artist"));
    }

    @Test
    public void miniLineReturnsNullForEmptyLyrics() {
        Lyrics empty = new Lyrics(List.of(), "TestProvider", false,
                null, null, null, null, null, null, null);
        assertNull(LyricsPresentation.miniLine(empty, 10000, 1000, "Song", "Artist"));
    }

    // ── setMainLineOnlyState ──

    @Test
    public void setMainLineOnlyStateShowsTranslation() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, 5000, "hello", List.of()));
        List<String> translatedLines = List.of("translated text");
        LyricsPresentation.setMainLineOnlyState(true, false, translatedLines, null);
        // Translation replaces the text; hidden :(x{n}): leaves a trailing space.
        assertEquals("translated text ",
                LyricsPresentation.miniLine(lyrics, 10000, 1000, "Song", "Artist"));
    }

    @Test
    public void setMainLineOnlyStateFallsBackToOriginalWithoutTranslation() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, 5000, "hello", List.of()));
        // translationOnly is true but the translation list is null → original text is shown.
        LyricsPresentation.setMainLineOnlyState(true, false, null, null);
        assertEquals("hello ", LyricsPresentation.miniLine(lyrics, 10000, 1000, "Song", "Artist"));
    }
}
