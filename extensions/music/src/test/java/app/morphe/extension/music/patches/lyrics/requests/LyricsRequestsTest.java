/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LyricsRequestsTest {

    // ── fractionToMs ──

    @Test
    public void fractionToMsSingleDigit() {
        // "5" means 5 tenths = 500ms
        assertEquals(500L, LyricsRequests.fractionToMs("5"));
    }

    @Test
    public void fractionToMsTwoDigits() {
        // "50" means 50 hundredths = 500ms
        assertEquals(500L, LyricsRequests.fractionToMs("50"));
    }

    @Test
    public void fractionToMsThreeDigits() {
        // "500" means 500 milliseconds
        assertEquals(500L, LyricsRequests.fractionToMs("500"));
    }

    @Test
    public void fractionToMsZero() {
        assertEquals(0L, LyricsRequests.fractionToMs("0"));
        assertEquals(0L, LyricsRequests.fractionToMs("00"));
        assertEquals(0L, LyricsRequests.fractionToMs("000"));
    }

    @Test
    public void fractionToMsNine() {
        assertEquals(900L, LyricsRequests.fractionToMs("9"));
        assertEquals(900L, LyricsRequests.fractionToMs("90"));
        assertEquals(900L, LyricsRequests.fractionToMs("900"));
    }

    // ── applyOffset ──

    @Test
    public void applyOffsetBasic() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of(
                        new Word(1000, 2000, "Hello"))));
        List<LyricsLine> adjusted = LyricsRequests.applyOffset(lines, 500);
        assertEquals(1500L, adjusted.get(0).startTimeMs());
        assertEquals(2500L, adjusted.get(0).endTimeMs());
        assertEquals(1500L, adjusted.get(0).words().get(0).startMs());
        assertEquals(2500L, adjusted.get(0).words().get(0).endMs());
    }

    @Test
    public void applyOffsetZeroOffset() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        List<LyricsLine> adjusted = LyricsRequests.applyOffset(lines, 0);
        // Zero offset returns the same list
        assertEquals(lines, adjusted);
    }

    @Test
    public void applyOffsetEmptyOrNull() {
        assertNull(LyricsRequests.applyOffset(null, 500));
        assertEquals(List.of(), LyricsRequests.applyOffset(List.of(), 500));
    }

    @Test
    public void applyOffsetPreservesTextAndAttributes() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of(),
                        "v1", true, false, "Verse"));
        List<LyricsLine> adjusted = LyricsRequests.applyOffset(lines, 500);
        LyricsLine line = adjusted.get(0);
        assertEquals("Hello", line.text());
        assertEquals("v1", line.agentId());
        assertTrue(line.isDuet());
        assertFalse(line.isBG());
        assertEquals("Verse", line.songPart());
    }

    @Test
    public void applyOffsetPreservesRubyAndRomaji() {
        List<Word> words = List.of(
                new Word(1000, 2000, "漢字", "kanji", false, "カンジ"));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "漢字", words));
        List<LyricsLine> adjusted = LyricsRequests.applyOffset(lines, 500);
        Word word = adjusted.get(0).words().get(0);
        assertEquals("漢字", word.text());
        assertEquals("kanji", word.romaji());
        assertEquals("カンジ", word.ruby());
    }

    // ── primarySubtag ──

    @Test
    public void primarySubtagBasic() {
        assertEquals("en", LyricsRequests.primarySubtag("en"));
        assertEquals("en", LyricsRequests.primarySubtag("en-US"));
        assertEquals("zh", LyricsRequests.primarySubtag("zh-Hans-CN"));
        assertEquals("ja", LyricsRequests.primarySubtag("ja-JP"));
    }

    @Test
    public void primarySubtagNull() {
        assertEquals("", LyricsRequests.primarySubtag(null));
    }

    @Test
    public void primarySubtagCaseInsensitive() {
        assertEquals("en", LyricsRequests.primarySubtag("EN"));
        assertEquals("zh", LyricsRequests.primarySubtag("ZH-Hans"));
    }

    // ── containsEither ──

    @Test
    public void containsEitherExactMatch() {
        assertTrue(LyricsRequests.containsEither("Hello", "Hello"));
    }

    @Test
    public void containsEitherSubstring() {
        assertTrue(LyricsRequests.containsEither("Hello World", "World"));
        assertTrue(LyricsRequests.containsEither("World", "Hello World"));
    }

    @Test
    public void containsEitherEmpty() {
        assertFalse(LyricsRequests.containsEither("", "Hello"));
        assertFalse(LyricsRequests.containsEither("Hello", ""));
    }

    @Test
    public void containsEitherNoMatch() {
        assertFalse(LyricsRequests.containsEither("Hello", "World"));
    }

    // ── isCjk ──

    @Test
    public void isCjkHan() {
        assertTrue(LyricsRequests.isCjk('你'));
        assertTrue(LyricsRequests.isCjk('好'));
    }

    @Test
    public void isCjkNotHan() {
        assertFalse(LyricsRequests.isCjk('A'));
        assertFalse(LyricsRequests.isCjk('こ'));
        assertFalse(LyricsRequests.isCjk('사'));
    }

    // ── syncRank ──

    @Test
    public void syncRankWithWordTimings() {
        List<Word> words = List.of(new Word(1000, 1500, "Hello"));
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", words)),
                "Provider", true, null, null, null, null, null, "lrc", null);
        assertEquals(2, LyricsRequests.syncRank(lyrics));
    }

    @Test
    public void syncRankSyncedNoWordTimings() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", List.of())),
                "Provider", true, null, null, null, null, null, "lrc", null);
        assertEquals(1, LyricsRequests.syncRank(lyrics));
    }

    @Test
    public void syncRankUnsynced() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(-1, "Hello", List.of())),
                "Provider", false, null, null, null, null, null, null, null);
        assertEquals(0, LyricsRequests.syncRank(lyrics));
    }

    // ── scoreSingleResult ──

    @Test
    public void scoreSingleResultSynced() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", List.of())),
                "Provider", true, null, null, null, null, null, "lrc", null);
        // 6 + syncRank(1) = 7
        assertEquals(7, LyricsRequests.scoreSingleResult(lyrics));
    }

    @Test
    public void scoreSingleResultWithWordTimings() {
        List<Word> words = List.of(new Word(1000, 1500, "Hello"));
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", words)),
                "Provider", true, null, null, null, null, null, "lrc", null);
        // 6 + syncRank(2) = 8
        assertEquals(8, LyricsRequests.scoreSingleResult(lyrics));
    }

    @Test
    public void scoreSingleResultUnsynced() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(-1, "Hello", List.of())),
                "Provider", false, null, null, null, null, null, null, null);
        // 6 + syncRank(0) = 6
        assertEquals(6, LyricsRequests.scoreSingleResult(lyrics));
    }

    // ── composite ──

    @Test
    public void compositeBasic() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", List.of())),
                "Provider", true, null, null, null, null, null, "lrc", null);
        // matchScore * 10 + syncRank(1) - 0 = 10 + 1 = 11 (if matchScore = 1)
        assertEquals(11, LyricsRequests.composite(1, lyrics, 0));
    }

    @Test
    public void compositeWithVariantPenalty() {
        Lyrics lyrics = new Lyrics(
                List.of(new LyricsLine(1000, 2000, "Hello", List.of())),
                "Provider", true, null, null, null, null, null, "lrc", null);
        // matchScore * 10 + syncRank(1) - 2 = 10 + 1 - 2 = 9 (if matchScore = 1)
        assertEquals(9, LyricsRequests.composite(1, lyrics, 2));
    }

    // ── normalizeForMatch ──

    @Test
    public void normalizeForMatchNull() {
        assertEquals("", LyricsRequests.normalizeForMatch(null));
    }

    @Test
    public void normalizeForMatchEmpty() {
        assertEquals("", LyricsRequests.normalizeForMatch(""));
    }

    @Test
    public void normalizeForMatchLowercases() {
        assertEquals("hello", LyricsRequests.normalizeForMatch("HELLO"));
    }

    @Test
    public void normalizeForMatchTrims() {
        assertEquals("hello", LyricsRequests.normalizeForMatch("  hello  "));
    }

    @Test
    public void normalizeForMatchFoldsFullwidthPunctuation() {
        // Fullwidth comma (，) folds to ASCII comma
        String result = LyricsRequests.normalizeForMatch("Hello，World");
        assertTrue(result.contains(","));
    }
}
