/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WordTest {

    @Test
    public void threeArgConstructorSetsDefaults() {
        Word word = new Word(0, 1000, "text");
        assertEquals(0L, word.startMs());
        assertEquals(1000L, word.endMs());
        assertEquals("text", word.text());
        assertNull(word.romaji());
        assertFalse(word.endsWithSpace());
        assertNull(word.ruby());
    }

    @Test
    public void fiveArgConstructorSetsRomajiAndSpace() {
        Word word = new Word(0, 1000, "text", "romaji", true);
        assertEquals(0L, word.startMs());
        assertEquals(1000L, word.endMs());
        assertEquals("text", word.text());
        assertEquals("romaji", word.romaji());
        assertTrue(word.endsWithSpace());
        assertNull(word.ruby());
    }

    @Test
    public void sixArgConstructorSetsAllFields() {
        Word word = new Word(0, 1000, "text", "romaji", true, "ruby");
        assertEquals(0L, word.startMs());
        assertEquals(1000L, word.endMs());
        assertEquals("text", word.text());
        assertEquals("romaji", word.romaji());
        assertTrue(word.endsWithSpace());
        assertEquals("ruby", word.ruby());
    }

    @Test
    public void fiveArgConstructorNullRomaji() {
        Word word = new Word(0, 1000, "text", null, false);
        assertNull(word.romaji());
        assertFalse(word.endsWithSpace());
        assertNull(word.ruby());
    }

    @Test
    public void zeroDurationWord() {
        Word word = new Word(500, 500, "text");
        assertEquals(500L, word.startMs());
        assertEquals(500L, word.endMs());
    }
}
