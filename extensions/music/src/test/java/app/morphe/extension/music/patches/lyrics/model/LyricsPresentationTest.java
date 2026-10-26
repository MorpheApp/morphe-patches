/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class LyricsPresentationTest {
    private static String clean(String text) {
        return LyricsTextFormatter.format(text, false, true).text();
    }

    @Test public void defaultsPreserveText() {
        assertEquals("hello.", LyricsTextFormatter.format("hello.", false, false).text());
        assertEquals("Title - Artist", MiniLyricsFormatter.trackLabel(null, "Title", "Artist"));
        assertEquals("hello.", MiniLyricsFormatter.encapsulate("{lrc}", "hello.", 1));
        assertEquals(1, MiniLyricsFormatter.chunks(new LyricsLine(0, "a long original line."),
                5000, 0, false, false).size());
    }


    @Test public void redundantPunctuationOnlyAtTheEnd() {
        assertEquals("I'm fine", clean("I'm fine."));
        assertEquals("Hello, how are you?", clean("Hello, how are you?"));
        assertEquals("I'm fin.e", clean("I'm fin.e"));
        assertEquals("Hello", clean("Hello,;:."));
        assertEquals("one\ntwo", clean("one.\ntwo,"));
    }

    @Test public void closingPairsStayIntact() {
        assertEquals("\"I'm fine\"", clean("\"I'm fine.\""));
        assertEquals("(hello)", clean("(hello.)"));
        assertEquals("‘hello’", clean("‘hello.’"));
        assertEquals("「你好」", clean("「你好。」"));
        assertEquals("(really?)", clean("(really?)"));
        assertEquals("\"I'm fin.e\"", clean("\"I'm fin.e\""));
    }

    @Test public void expressivePunctuationIsNeverDiscarded() {
        for (String line : List.of("hello!", "really?!", "wait...", "wait…", "wait..",
                "حقاً؟", "שלום!", "本当？", "αλήθεια;", "wow‼", "wait—")) {
            assertEquals(line, clean(line));
        }
    }

    @Test public void unicodeSentenceEndings() {
        assertEquals("你好", clean("你好。"));
        assertEquals("你好", clean("你好，"));
        assertEquals("مرحبا", clean("مرحبا،"));
        assertEquals("مرحبا", clean("مرحبا؛"));
        assertEquals("Բարեւ", clean("Բարեւ։"));
        assertEquals("नमस्ते", clean("नमस्ते।"));
        assertEquals("hello", clean("hello．"));
    }

    @Test public void capitalizeFirstCasedLetterAndPreserveRest() {
        assertEquals("♪ Élan is HERE\n«Привет»\n你好", LyricsTextFormatter.format(
                "♪ élan is HERE\n«привет»\n你好", true, false).text());
        assertEquals("SSong", LyricsTextFormatter.format("ßong", true, false).text());
    }

    @Test public void offsetMapFollowsCaseExpansionAndDeletion() {
        LyricsTextFormatter.Result result = LyricsTextFormatter.format("ßong.\nhi.", true, true);
        assertEquals("SSong\nHi", result.text());
        assertEquals(2, result.offset(1));
        assertEquals(5, result.offset(4));
        assertEquals(5, result.offset(5));
        assertEquals(6, result.offset(6));
        assertEquals(8, result.offset(9));
        assertEquals(-1, result.offset(-1));
    }

    @Test public void templatesAreLiteralAndNonRecursive() {
        assertEquals("{a} • X", MiniLyricsFormatter.trackLabel("{t} • {a}", "{a}", "X"));
        assertEquals("A / T", MiniLyricsFormatter.trackLabel("{a} / {t}", "T", "A"));
        assertEquals("{unknown} T", MiniLyricsFormatter.trackLabel("{unknown} {t}", "T", "A"));
        assertEquals("♪ hello ♪", MiniLyricsFormatter.encapsulate("♪ {lrc} ♪", "hello", 1));
        assertEquals("hello ", MiniLyricsFormatter.encapsulate("invalid", "hello", 1));
        assertFalse(MiniLyricsFormatter.validEncapsulation("invalid"));
        assertTrue(MiniLyricsFormatter.validEncapsulation("{lrc} / {lrc}"));
        assertEquals("hello / hello", MiniLyricsFormatter.encapsulate("{lrc} / {lrc}", "hello", 1));
    }

    @Test public void limitTriggersTwoWordBalancedHalves() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "Build me up, don't get me down"), 10000, 15, false, false);
        assertEquals(List.of("Build me up, don't", "get me down"),
                chunks.stream().map(MiniLyricsFormatter.Chunk::text).toList());
        assertEquals(2, chunks.size()); // Limit triggers splitting; it is not a hard cutoff.
    }

    @Test public void longWordsRemainWholeButUnspacedScriptsCanSplit() {
        assertEquals(List.of("one extraordinarilylong", "end"), MiniLyricsFormatter.chunks(
                new LyricsLine(0, "one extraordinarilylong end"), 5000, 5, false, false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
        assertEquals(List.of("你好世" + MiniLyricsFormatter.SPLIT_ELLIPSIS, "界再见"), MiniLyricsFormatter.chunks(
                new LyricsLine(0, "你好世界再见"), 5000, 3, false, false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
    }

    @Test public void graphemeClustersAreNotBroken() {
        assertEquals(1, MiniLyricsFormatter.characterCount("👩‍👩‍👧‍👦"));
        assertEquals(1, MiniLyricsFormatter.characterCount("e\u0301"));
        assertEquals(1, MiniLyricsFormatter.characterCount("🇺🇸"));
        assertEquals(2, MiniLyricsFormatter.chunks(new LyricsLine(0, "🇺🇸🇨🇦"),
                1000, 1, false, false).size());
    }

    @Test public void wordTimesDriveChunkChangesAndSeeking() {
        LyricsLine line = new LyricsLine(1000, 7000, "one two three four", List.of(
                new Word(1000, 2000, "one"), new Word(2000, 3000, "two"),
                new Word(4500, 5000, "three"), new Word(5000, 6000, "four")));
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(line, 7000, 7, false, false);
        assertEquals("one two", MiniLyricsFormatter.current(chunks, 4499, "{lrc}", 1));
        assertEquals("three four", MiniLyricsFormatter.current(chunks, 4500, "{lrc}", 1));
        assertEquals("three four", MiniLyricsFormatter.current(chunks, 5000, "{lrc}", 1));
        assertEquals("one two", MiniLyricsFormatter.current(chunks, 1500, "{lrc}", 1));
    }

    @Test public void wrapperDoesNotConsumeBodyBudget() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "hello world"), 5000, 5, false, false);
        assertEquals("♪ hello ♪", MiniLyricsFormatter.current(chunks, 0, "♪ {lrc} ♪", 1));
        assertEquals("♪ world ♪", MiniLyricsFormatter.current(chunks, 4999, "♪ {lrc} ♪", 1));
    }

    @Test public void unknownEndDoesNotInventTiming() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "hello world"), -1, 5, false, false);
        assertEquals("hello", MiniLyricsFormatter.current(chunks, 999999, "{lrc}", 1));
    }

    @Test public void originalModelIsNotMutated() {
        LyricsLine line = new LyricsLine(1000, "hello there.");
        MiniLyricsFormatter.chunks(line, 5000, 5, true, true);
        assertEquals("hello there.", line.text());
        assertEquals(1000, line.startTimeMs());
    }

    @Test public void timedWordsRetainUnalignedPunctuation() {
        LyricsLine line = new LyricsLine(0, 4000, "hello, world.", List.of(
                new Word(0, 1000, "hello"), new Word(2000, 3000, "world.")));
        assertEquals(List.of("hello,", "world."), MiniLyricsFormatter.chunks(line, 4000, 6, false, false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
    }

    @Test public void incompleteWordListCannotDiscardMiddleContent() {
        LyricsLine line = new LyricsLine(0, 4000, "one missing two", List.of(
                new Word(0, 1000, "one"), new Word(2000, 3000, "two")));
        assertEquals(List.of("one missing", "two"), MiniLyricsFormatter.chunks(line, 4000, 5, false, false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
    }

    @Test public void formattingIsAfterSplittingButBeforeDecoration() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "hello there."), 5000, 5, true, true);
        assertEquals("♪ Hello. ♪", MiniLyricsFormatter.current(chunks, 0, "♪ {lrc}. ♪", 1));
        assertEquals("♪ There. ♪", MiniLyricsFormatter.current(chunks, 4999, "♪ {lrc}. ♪", 1));
    }

    @Test public void punctuationAtANewChunkBoundaryIsAlsoTrailing() {
        List<MiniLyricsFormatter.Chunk> chunks = MiniLyricsFormatter.chunks(
                new LyricsLine(0, "one two, three four."), 10000, 10, false, true);
        assertEquals("one two", chunks.get(0).text());
        assertEquals("three four", chunks.get(1).text());
    }
}
