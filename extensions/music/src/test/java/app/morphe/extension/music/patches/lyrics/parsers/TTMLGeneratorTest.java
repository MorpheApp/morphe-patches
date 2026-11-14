/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.SkipSegments;
import app.morphe.extension.music.patches.lyrics.model.CreditInfo;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class TTMLGeneratorTest {

    @BeforeClass
    public static void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void generateBasicSyncedLyrics() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null),
                new Word(1500, 2000, "world", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello world", words1, null, false, false, "Verse"));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull("TTML should not be null", ttml);
        assertTrue(ttml.contains("Test Song"));
        assertTrue(ttml.contains("Test Artist"));
        assertTrue(ttml.contains("Test Album"));
        assertTrue(ttml.contains("TestProvider"));
        assertTrue(ttml.contains("morphe:source"));
        assertTrue(ttml.contains("itunes:songPart=\"Verse\""));
    }

    @Test
    public void generateNullLyricsReturnsNull() {
        assertNull(TTMLGenerator.generate(null, null, null));
    }

    @Test
    public void generateEmptyLyricsReturnsNull() {
        Lyrics lyrics = new Lyrics(List.of(), "TestProvider", false,
                null, null, null, null, null, null, null, null, null);
        assertNull(TTMLGenerator.generate(lyrics, null, null));
    }

    @Test
    public void generateWithCreditInfo() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);
        CreditInfo credit = new CreditInfo("Google", "AI:gpt-4");

        String ttml = TTMLGenerator.generate(lyrics, track, credit);
        assertNotNull(ttml);
        assertTrue(ttml.contains("morphe:source"));
        assertTrue(ttml.contains("morphe:translator"));
        assertTrue(ttml.contains("Google"));
        assertTrue(ttml.contains("morphe:romanizer"));
        assertTrue(ttml.contains("AI:gpt-4"));
    }

    @Test
    public void generateWithSongwriters() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        List<String> songwriters = List.of("John Doe", "Jane Smith");
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, songwriters, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("songwriter"));
        assertTrue(ttml.contains("John Doe"));
        assertTrue(ttml.contains("Jane Smith"));
    }

    @Test
    public void generateWithTranslations() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        List<LyricsLine> transEs = List.of(
                new LyricsLine(-1, -1, "Hola", List.of(), null, false, false, null));
        Map<String, List<LyricsLine>> translations = new HashMap<>();
        translations.put("es", transEs);
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, translations, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("x-translation"));
        assertTrue(ttml.contains("xml:lang=\"es\""));
        assertTrue(ttml.contains("Hola"));
    }

    @Test
    public void generateWithRuby() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "漢字", null, false, "カンジ"));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "漢字", words1, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("tts:ruby"));
        assertTrue(ttml.contains("漢字"));
        assertTrue(ttml.contains("カンジ"));
    }

    @Test
    public void generateWithBgVocals() {
        List<Word> mainWords = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<Word> bgWords = List.of(
                new Word(1000, 1400, "world", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", mainWords, null, false, false, null),
                new LyricsLine(1000, 1400, "world", bgWords, null, false, true, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("x-bg"));
        assertTrue(ttml.contains("world"));
    }

    @Test
    public void generateUnsyncedLyrics() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(-1, -1, "Hello world", List.of(), null, false, false, null),
                new LyricsLine(-1, -1, "This is a test", List.of(), null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", false,
                null, null, null, null, null, null, null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("Hello world"));
        assertTrue(ttml.contains("This is a test"));
    }

    @Test
    public void generateWithMultipleSongParts() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<Word> words2 = List.of(
                new Word(2000, 2500, "Chorus", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, "Verse"),
                new LyricsLine(2000, 2500, "Chorus", words2, null, false, false, "Chorus"));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("itunes:songPart=\"Verse\""));
        assertTrue(ttml.contains("itunes:songPart=\"Chorus\""));
    }

    @Test
    public void generateWithSourceUrl() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", "https://example.com", null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("morphe:sourceUrl"));
        assertTrue(ttml.contains("https://example.com"));
    }

    @Test
    public void generateWithAbout() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, "About text", null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("About text"));
    }

    @Test
    public void generateWithLrcMetadata() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        String rawFormat = """
                [ti:Test Song]
                [ar:Test Artist]
                [al:Test Album]
                [by:Uploader]
                [length:180]
                [re:Remastered]
                [kana:カナ]
                [ve:1.0]
                """;
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, rawFormat, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        // [by:] goes to <morphe:credits>, not <songwriters>
        assertTrue(ttml.contains("morphe:credits"));
        assertTrue(ttml.contains("Uploader"));
        // ti/ar/al match TrackInfo, so no duplicate <title>/<artist>/<album> from LRC
        assertTrue(ttml.contains("morphe:length"));
        assertTrue(ttml.contains("morphe:re"));
        assertTrue(ttml.contains("Remastered"));
        assertTrue(ttml.contains("morphe:kana"));
        assertTrue(ttml.contains("morphe:ve"));
    }

    @Test
    public void generateWithLrcMetadataFallback() {
        // When TrackInfo lacks title/artist/album, LRC [ti:]/[ar:]/[al:] fill the standard fields.
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        String rawFormat = """
                [ti:Lrc Title]
                [ar:Lrc Artist]
                [al:Lrc Album]
                [au:Submitter]
                """;
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, rawFormat, "lrc", null, null, null);
        TrackInfo track = new TrackInfo(null, null, null, 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        // LRC ti/ar/al fill standard TTML fields
        assertTrue(ttml.contains("<title>Lrc Title</title>"));
        assertTrue(ttml.contains("<artist>Lrc Artist</artist>"));
        assertTrue(ttml.contains("<album>Lrc Album</album>"));
        // [au:] goes to <morphe:credits>
        assertTrue(ttml.contains("morphe:credits"));
        assertTrue(ttml.contains("Submitter"));
    }

    @Test
    public void generateWithBodyCredits() {
        // Credit lines from the lyrics body (e.g. "词：xxx") go to <morphe:credits>,
        // not <songwriters>.
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words1, null, false, false, null));
        List<String> credits = List.of("词：宋健彰", "曲：周杰伦");
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, credits, "lrc", null, null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        // Credits go to <morphe:credits>, not <songwriters>
        assertTrue(ttml.contains("morphe:credits"));
        assertTrue(ttml.contains("词：宋健彰"));
        assertTrue(ttml.contains("曲：周杰伦"));
        // No <songwriters> section since songwriters is null
        assertFalse(ttml.contains("<songwriters>"));
    }

    @Test
    public void generateWithAgents() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Duet", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Duet", words1, "singer1", false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("<agent"));
        assertTrue(ttml.contains("singer1"));
        assertTrue(ttml.contains("ttm:type"));
    }

    @Test
    public void generateWithAgentNames() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Duet", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Duet", words1, "singer1", false, false, null));
        Map<String, String> agentNames = Map.of("singer1", "Addison Rae");
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, agentNames);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue("Should contain ttm:name", ttml.contains("ttm:name"));
        assertTrue("Should contain agent display name", ttml.contains("Addison Rae"));
        assertFalse("Should not leak raw agent ID as name", ttml.contains("<ttm:name>singer1</ttm:name>"));
    }

    @Test
    public void generateWithAgentNoNameFallsBackToSelfClosing() {
        List<Word> words1 = List.of(
                new Word(1000, 1500, "Duet", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Duet", words1, "singer1", false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue("Should contain agent element", ttml.contains("<agent"));
        assertFalse("Should not contain ttm:name without names", ttml.contains("ttm:name"));
    }

    @Test
    public void wordSpansWithSpacesAddSpaceBetweenSpans() {
        // Words with endsWithSpace=true should produce a space between spans
        List<Word> words = List.of(
                new Word(1000, 1500, "Oh", null, true, null),
                new Word(1500, 2000, "nothin'", null, true, null),
                new Word(2000, 2500, "can", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2500, "Oh nothin' can", words, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        // Space after "Oh</span>" and "nothin'</span>" but not after "can</span>"
        assertTrue("Should have space between word spans",
                ttml.contains(">Oh</span> <span"));
        assertTrue("Should have space between second and third word spans",
                ttml.contains(">nothin'</span> <span"));
        // Last word should not have trailing space
        assertTrue("Last word should not have trailing space",
                ttml.contains(">can</span>"));
    }

    @Test
    public void thaiWordSpansPreserveEndsWithSpace() {
        // Thai words with endsWithSpace=true must keep the space between spans,
        // matching Apple Music's reference TTML output.
        List<Word> words = List.of(
                new Word(1000, 1500, "\u0e17\u0e33", null, false, null),
                new Word(1500, 2000, "\u0e19\u0e23\u0e40\u0e01\u0e2d\u0e25", null, true, null),
                new Word(2000, 2500, "\u0e19\u0e07\u0e2d\u0e1a", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2500, "\u0e17\u0e33 \u0e19\u0e23\u0e40\u0e01\u0e2d\u0e25 \u0e19\u0e07\u0e2d\u0e1a", words, null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null);
        assertNotNull(ttml);
        assertTrue("Thai word with endsWithSpace should produce a space between spans",
                ttml.contains("\u0e19\u0e23\u0e40\u0e01\u0e2d\u0e25</span> <span"));
        assertTrue("First Thai word without endsWithSpace should not have trailing space",
                ttml.contains("\u0e17\u0e33</span><span"));
        assertTrue("Last Thai word should not have trailing space",
                ttml.contains("\u0e19\u0e07\u0e2d\u0e1a</span>"));
    }

    @Test
    public void unsyncedLyricsCarryNoTimes() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(-1, -1, "Hello world", List.of(), null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", false,
                null, null, null, null, null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("itunes:timing=\"None\""));
        assertFalse("A line without a time must not be written at zero", ttml.contains("begin="));
    }

    @Test
    public void lineWithoutSectionStaysInTheSectionBeforeIt() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "One", List.of(), null, false, false, "Chorus"),
                new LyricsLine(2000, 2500, "Two", List.of(), null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertEquals(ttml.indexOf("<div"), ttml.lastIndexOf("<div"));
        assertTrue(ttml.contains("itunes:songPart=\"Chorus\""));
    }

    @Test
    public void lyricsWithoutSectionsNameNone() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "One", List.of(), null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertFalse(ttml.contains("songPart"));
    }

    @Test
    public void wordSyncedLineKeepsItsWholeLineReading() {
        List<Word> words = List.of(new Word(1000, 1500, "愛", null, false));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "愛", words, null, false, false, null));
        List<LyricsLine> reading = List.of(new LyricsLine(1000, "ai"));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                reading, null, null, null, null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("<span ttm:role=\"x-roman\" xml:lang=\"romaji\">ai</span>"));
    }

    @Test
    public void cjkReadingDoesNotSeparateBodyWordSpans() {
        // A word-synced Chinese line keeps its characters together in the body even when
        // the reading is per syllable; only the romanization carries the spaces.
        List<Word> words = List.of(
                new Word(0, 100, "成"),
                new Word(100, 200, "長"),
                new Word(200, 300, "的"),
                new Word(300, 400, "沈"),
                new Word(400, 500, "痛"));
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, 500, "成長的沈痛", words, null, false, false, null));

        List<Word> readingWords = List.of(
                new Word(0, 100, "成", "chéng", false),
                new Word(100, 200, "長", "cháng", false),
                new Word(200, 300, "的", "de", false),
                new Word(300, 400, "沈", "shěn", false),
                new Word(400, 500, "痛", "tòng", false));
        List<LyricsLine> romanized = List.of(
                new LyricsLine(0, 500, "chéng cháng de shěn tòng", readingWords,
                        null, false, false, null));

        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, null, null);
        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0),
                null, null, romanized, null, null);
        assertNotNull(ttml);
        String body = ttml.substring(ttml.indexOf("<body>"), ttml.indexOf("</body>"));
        assertFalse("CJK reading characters must not be separated by spaces in the body",
                body.contains("</span> <span begin="));
        assertTrue(ttml.contains(
                "<span ttm:role=\"x-roman\" xml:lang=\"romaji\">chéng cháng de shěn tòng</span>"));
    }

    @Test
    public void backgroundReadingStaysOffTheMainLine() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Main", List.of(), null, false, false, null));
        Map<String, List<LyricsLine>> romanizations = Map.of(
                "bg:ja-Latn", List.of(new LyricsLine(1000, "bgreading")));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true,
                null, null, romanizations, null, null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertFalse(ttml.contains("bgreading"));
    }

    @Test
    public void copyrightCreditFillsTheCopyrightField() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Main", List.of(), null, false, false, null));
        Lyrics lyrics = new Lyrics(lines, "TestProvider", true, null, null, null,
                null, List.of("Copyright: Label"), null, "lrc", null, null, null);

        String ttml = TTMLGenerator.generate(lyrics, new TrackInfo("Song", "Artist", null, 0), null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("<copyright>Label</copyright>"));
        assertFalse(ttml.contains("morphe:credits"));
    }

    private static Lyrics singleLineLyrics() {
        List<Word> words = List.of(new Word(1000, 1500, "Hello", null, false, null));
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 1500, "Hello", words, null, false, false, null));
        return new Lyrics(lines, "TestProvider", true,
                null, null, null, null, null, "lrc", null, null, null);
    }

    @Test
    public void zeroTemporaryOffsetOmitsTheAudioElement() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 0, null);
        assertNotNull(ttml);
        assertFalse("Zero offset must not create an empty iTunesMetadata block",
                ttml.contains("iTunesMetadata"));
        assertFalse("No audio tag should remain",
                ttml.contains("<audio"));
    }

    @Test
    public void temporaryOffsetShiftsTimestamps() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 940, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("begin=\"00:01.940\""));
        assertTrue(ttml.contains("end=\"00:02.440\""));
        assertFalse("No audio tag should remain", ttml.contains("<audio"));
    }

    @Test
    public void negativeOffsetClampedToZero() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, -1500, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("begin=\"00:00.000\""));
    }

    @Test
    public void sponsorBlockSegmentsRemapTimestamps() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(0, 2000, "sponsor", 0));

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 0, segs);
        assertNotNull(ttml);
        assertTrue(ttml.contains("begin=\"00:03.000\""));
        assertTrue(ttml.contains("end=\"00:03.500\""));
    }

    @Test
    public void offsetAndSponsorBlockCombined() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);
        List<SkipSegments.Seg> segs = List.of(new SkipSegments.Seg(0, 2000, "sponsor", 0));

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 940, segs);
        assertNotNull(ttml);
        assertTrue(ttml.contains("begin=\"00:03.940\""));
        assertTrue(ttml.contains("end=\"00:04.440\""));
    }

    @Test
    public void nullSegmentsGracefulDegradation() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 940, null);
        assertNotNull(ttml);
        assertTrue(ttml.contains("begin=\"00:01.940\""));
    }

    @Test
    public void offsetAloneDoesNotCreateItunesMetadata() {
        Lyrics lyrics = singleLineLyrics();
        TrackInfo track = new TrackInfo("Test Song", "Test Artist", "Test Album", 180);

        String ttml = TTMLGenerator.generate(lyrics, track, null, null, null, null, null, 940, null);
        assertNotNull(ttml);
        assertFalse("Offset alone must not create iTunesMetadata",
                ttml.contains("iTunesMetadata"));
    }
}
