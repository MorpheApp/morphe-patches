package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.derive.LyricsFuriganaGenerator;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;

public class FuriganaCoreTest {

    @Test
    public void testKanaRomanizeSyllables() {
        assertEquals("no ru ma", KanaRomanizer.romanizeSyllables("ノルマ"));
        assertEquals("i jo u", KanaRomanizer.romanizeSyllables("いじょう"));
        assertEquals("fu ka ku", KanaRomanizer.romanizeSyllables("ふかく"));
    }

    @Test
    public void testFormatRuby() {
        assertEquals("Normal", LyricsFuriganaGenerator.formatRuby("Normal", "HIRAGANA"));
        assertEquals("Normal", LyricsFuriganaGenerator.formatRuby("Normal", "KATAKANA"));
        assertEquals("じょう", LyricsFuriganaGenerator.formatRuby("ジョウ", "HIRAGANA"));
        assertEquals("ジョウ", LyricsFuriganaGenerator.formatRuby("じょう", "KATAKANA"));
    }

    @Test
    public void testAiBracketEnrichment() {
        List<Word> inWords = Arrays.asList(
                new Word(1000, 3000, "深く", null, true, null),
                new Word(3000, 5000, "朱き", null, false, null)
        );
        LyricsLine line = new LyricsLine(1000, 5000, "深く 朱き", inWords);
        Lyrics lyrics = new Lyrics(Collections.singletonList(line), "LRCLIB", true, null, null, null, null, null, null, null);
        Lyrics enriched = LyricsFuriganaGenerator.enrichFromAiLines(
                lyrics, Collections.singletonList("深く[ふかく] 朱き[あかき]"), "HIRAGANA");

        List<Word> words = enriched.lines().get(0).words();
        StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            sb.append("[").append(w.text()).append(":").append(w.ruby()).append("]");
        }
        assertEquals("[深く:ふか][朱き:あか]", sb.toString());
    }

    @Test
    public void testRubyPassthroughInParsersAndModel() {
        List<Word> words = Arrays.asList(
                new Word(1000, LyricsLine.NO_TIME, "漢字", "ka n ji", false, "かんじ"),
                new Word(1500, LyricsLine.NO_TIME, "テスト", "te su to", false, "てすと")
        );
        LRCParser.inferMissingWordEnds(words, 500L);
        assertEquals("かんじ", words.get(0).ruby());
        assertEquals("てすと", words.get(1).ruby());

        LyricsLine line = new LyricsLine(1000, 5000, "漢字 テスト", words);
        Lyrics lyrics = new Lyrics(Collections.singletonList(line), "AMLL", true, null, null, null, null, null, null, null);

        List<LyricsLine> clamped = Lyrics.clampLastWordEnds(lyrics.lines());
        assertEquals("かんじ", clamped.get(0).words().get(0).ruby());

        Lyrics splitBg = LyricsBracketSplitter.apply(lyrics.withLines(clamped));
        assertEquals("かんじ", splitBg.lines().get(0).words().get(0).ruby());
    }
}
