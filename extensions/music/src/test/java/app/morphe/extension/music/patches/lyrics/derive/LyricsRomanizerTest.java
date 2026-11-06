/* Copyright 2026 Morphe. See the included NOTICE file for GPLv3 Section 7 terms that apply to this code. */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;

/** Tests for the public static helpers of {@link LyricsRomanizer}; fixtures are built directly and the extractors read {@code Settings.LYRICS_ALLOW_TRANSLITERATION}. */
public class LyricsRomanizerTest {

    @Before
    public void setUp() {
        // Some paths read Settings.LYRICS_ALLOW_TRANSLITERATION; install the fake context first.
        LyricsTestEnv.bootstrap();
    }

    private static LyricsLine wordlessLine(long startMs, long endMs, String text) {
        return new LyricsLine(startMs, endMs, text, List.of());
    }

    // ── isPurelyLatinScript ──

    @Test
    public void nullAndEmptyArePurelyLatin() {
        assertTrue(LyricsRomanizer.isPurelyLatinScript(null));
        assertTrue(LyricsRomanizer.isPurelyLatinScript(""));
    }

    @Test
    public void latinPunctuationAndWhitespaceArePurelyLatin() {
        assertTrue(LyricsRomanizer.isPurelyLatinScript("Hello world"));
        assertTrue(LyricsRomanizer.isPurelyLatinScript("Ça va, 1, 2, 3!"));
        assertTrue(LyricsRomanizer.isPurelyLatinScript("!?.-()[]{}#@"));
        assertTrue(LyricsRomanizer.isPurelyLatinScript("   \t\n  "));
    }

    @Test
    public void nonLatinScriptsAreNotPurelyLatin() {
        assertFalse(LyricsRomanizer.isPurelyLatinScript("こんにちは"));
        assertFalse(LyricsRomanizer.isPurelyLatinScript("사랑해"));
        assertFalse(LyricsRomanizer.isPurelyLatinScript("你好"));
        assertFalse(LyricsRomanizer.isPurelyLatinScript("Привет"));
        assertFalse(LyricsRomanizer.isPurelyLatinScript("Hello 世界"));
    }

    // ── wordReading ──

    @Test
    public void latinRomajiIsReturned() {
        // A Latin reading is always usable, with or without the transliteration gate.
        Word word = new Word(0, 100, "愛してる", "aishiteru", false);
        assertEquals("aishiteru", LyricsRomanizer.wordReading(word, true));
        assertEquals("aishiteru", LyricsRomanizer.wordReading(word, false));
    }

    @Test
    public void kanaAndHangulReadingsAreRomanized() {
        // Kana is romanized when transliteration is off; hangul is always romanized.
        Word kana = new Word(0, 100, "こんにちは", "こんにちは", false);
        assertEquals("konnichiha", LyricsRomanizer.wordReading(kana, false));

        Word hangul = new Word(0, 100, "사랑해", "사랑해", false);
        assertEquals("salanghae", LyricsRomanizer.wordReading(hangul, false));
    }

    @Test
    public void wordWithoutReadingReturnsEmpty() {
        // A Latin word with no reading has no phonetic fallback.
        Word word = new Word(0, 100, "Hello", null, false);
        assertEquals("", LyricsRomanizer.wordReading(word, true));
        assertEquals("", LyricsRomanizer.wordReading(word, false));
    }

    @Test
    public void hanReadingWithoutTransliterationReturnsEmpty() {
        // A purely Han reading needs the transliteration gate; off means no kana/hangul fallback.
        Word word = new Word(0, 100, "愛", "愛", false);
        assertEquals("", LyricsRomanizer.wordReading(word, false));
        assertEquals("愛", LyricsRomanizer.wordReading(word, true));
    }

    @Test
    public void degradedRomajiTokenReturnsEmpty() {
        // A doubled consonant is a broken mora marker, and the Latin text has no fallback.
        Word word = new Word(0, 100, "hello", "pp", false);
        assertEquals("", LyricsRomanizer.wordReading(word, true));
        assertEquals("", LyricsRomanizer.wordReading(word, false));
    }

    // ── getEmbeddedRomanization ──

    @Test
    public void legacyRomanizationReturnsLines() {
        LyricsLine original = wordlessLine(0, 1000, "こんにちは");
        LyricsLine reading = wordlessLine(0, 1000, "konnichiwa");
        Lyrics lyrics = new Lyrics(List.of(original), "TestProvider", true,
                List.of(reading), null, null, null, null, null, null);

        List<LyricsLine> result = LyricsRomanizer.getEmbeddedRomanization(lyrics);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("konnichiwa", result.get(0).text());
    }

    @Test
    public void romanizationsMapReturnsLines() {
        // Pin a language so collectMatchingRomanizations doesn't call Requester.getAppLocale() (Logger NPEs without a main looper).
        Settings.LYRICS_ROMANIZE_LANGUAGE.save("ja");
        LyricsLine original = wordlessLine(0, 1000, "こんにちは");
        LyricsLine reading = wordlessLine(0, 1000, "konnichiwa");
        Map<String, List<LyricsLine>> romanizations = new HashMap<>();
        romanizations.put("ja", List.of(reading));
        Lyrics lyrics = new Lyrics(List.of(original), "TestProvider", true,
                null, null, romanizations, null, null, null, null);

        List<LyricsLine> result = LyricsRomanizer.getEmbeddedRomanization(lyrics);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("konnichiwa", result.get(0).text());
    }

    @Test
    public void perWordRomajiReturnsLines() {
        Word word = new Word(0, 100, "愛", "aishiteru", false);
        LyricsLine line = new LyricsLine(0, 1000, "愛", List.of(word), null, false, false, null);
        Lyrics lyrics = new Lyrics(List.of(line), "TestProvider", true,
                null, null, null, null, null, null, null);

        List<LyricsLine> result = LyricsRomanizer.getEmbeddedRomanization(lyrics);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("aishiteru", result.get(0).text());
    }

    @Test
    public void lyricsWithoutRomanizationReturnsNull() {
        LyricsLine line = wordlessLine(0, 1000, "こんにちは");
        Lyrics lyrics = new Lyrics(List.of(line), "TestProvider", true,
                null, null, null, null, null, null, null);

        assertNull(LyricsRomanizer.getEmbeddedRomanization(lyrics));
    }

    // ── isProviderRomajiDegraded ──

    @Test
    public void validReadingIsNotDegraded() {
        // A Latin reading of a Japanese lyric covers the line and is not flagged.
        List<LyricsLine> original = List.of(wordlessLine(0, 1000, "愛してる"));
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "aishiteru"));
        assertFalse(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, true));
    }

    @Test
    public void moraFragmentsAreDegraded() {
        // A reading split into lone consonants (non-Japanese morae) means the morae fell apart.
        List<LyricsLine> original = List.of(wordlessLine(0, 1000, "愛してる"));
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "k s t m r"));
        assertTrue(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, true));
    }

    @Test
    public void straySymbolsAreDegraded() {
        // A bare symbol the lyric does not contain is an artifact of the reading.
        List<LyricsLine> original = List.of(wordlessLine(0, 1000, "愛してる"));
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "^"));
        assertTrue(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, true));
    }

    @Test
    public void pinyinAgainstJapaneseIsDegraded() {
        // A Mandarin pinyin reading shipped for a Japanese lyric is the wrong song.
        List<LyricsLine> original = List.of(wordlessLine(0, 1000, "愛してる"));
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "zhang wang"));
        assertTrue(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, true));
    }

    @Test
    public void lowCoverageIsDegraded() {
        // A one-line reading for a six-line lyric covers too little to be a reading.
        List<LyricsLine> original = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            original.add(wordlessLine(i * 1000L, (i + 1) * 1000L, "line " + i));
        }
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "validreading"));
        assertTrue(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, true));
    }

    @Test
    public void nonLatinWithoutTransliterationIsDegraded() {
        // With the transliteration gate off, a non-Latin reading is rejected.
        List<LyricsLine> original = List.of(wordlessLine(0, 1000, "愛してる"));
        List<LyricsLine> embedded = List.of(wordlessLine(0, 1000, "愛してる"));
        assertTrue(LyricsRomanizer.isProviderRomajiDegraded(embedded, original, false, false));
    }
}
