/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4323
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsLanguageDetectorTest {

    private static List<LyricsLine> lines(String... texts) {
        final List<LyricsLine> lines = new ArrayList<>();
        long time = 0;
        for (String text : texts) {
            lines.add(new LyricsLine(time, time + 1000, text, List.of()));
            time += 1000;
        }
        return lines;
    }

    @Test
    public void latinLyricsAreTranslatedIntoALatinLanguage() {
        // Script alone can't tell Spanish from English.
        assertTrue(LyricsLanguageDetector.needsTranslation(lines("Hola mi amor"), "en"));
    }

    @Test
    public void cyrillicLyricsAreTranslatedIntoACyrillicLanguage() {
        assertTrue(LyricsLanguageDetector.needsTranslation(lines("Привіт"), "ru"));
    }

    @Test
    public void unlistedScriptsAreTranslated() {
        assertTrue(LyricsLanguageDetector.needsTranslation(lines("வணக்கம்"), "ta"));
    }

    @Test
    public void lyricsInTheTargetScriptAreSkipped() {
        assertFalse(LyricsLanguageDetector.needsTranslation(lines("사랑해 baby", "♪"), "ko"));
        assertFalse(LyricsLanguageDetector.needsTranslation(lines("愛してる"), "ja"));
    }

    @Test
    public void japaneseLyricsAreTranslatedIntoChinese() {
        assertTrue(LyricsLanguageDetector.needsTranslation(lines("我爱你", "愛してる"), "zh"));
    }

    @Test
    public void englishLinesInAKoreanSongAreTranslatedIntoKorean() {
        assertTrue(LyricsLanguageDetector.needsTranslation(lines("사랑해", "I love you"), "ko"));
    }

    @Test
    public void latinLyricsNeedNoRomanization() {
        assertFalse(LyricsLanguageDetector.needsRomanization(lines("Hello world", "Ça va", "1, 2, 3")));
    }

    @Test
    public void anyOtherScriptNeedsRomanization() {
        assertTrue(LyricsLanguageDetector.needsRomanization(lines("Hello", "வணக்கம்")));
        assertTrue(LyricsLanguageDetector.needsRomanization(lines("Привет")));
        assertTrue(LyricsLanguageDetector.needsRomanization(lines("你好")));
    }
}
