/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class CldrTransformsTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        CldrTransforms.clear();
    }

    @Test
    public void readerReturnsNullWhenNoFileExists() {
        // Without a downloaded table file, reader returns null.
        assertNull(CldrTransforms.reader("greek"));
        assertNull(CldrTransforms.reader("cyrillic"));
        assertNull(CldrTransforms.reader("nonexistent_script"));
    }

    @Test
    public void clearDoesNotThrow() {
        CldrTransforms.clear();
        CldrTransforms.clear();
    }

    @Test
    public void readerCallAfterClearReturnsNull() {
        CldrTransforms.clear();
        assertNull(CldrTransforms.reader("greek"));
    }
}
