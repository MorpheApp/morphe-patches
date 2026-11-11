/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class LyricsDirectoriesTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void directoryCreatesAndReturnsRomanceDir() {
        File dir = LyricsDirectories.directory(LyricsDirectories.ROMAN_DICT);
        assertNotNull(dir);
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }

    @Test
    public void directoryCreatesAndReturnsOfflineDir() {
        File dir = LyricsDirectories.directory(LyricsDirectories.OFFLINE);
        assertNotNull(dir);
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }

    @Test
    public void directoryReturnsExistingDir() {
        File first = LyricsDirectories.directory("test_dir");
        assertNotNull(first);
        File second = LyricsDirectories.directory("test_dir");
        assertNotNull(second);
        assertEquals(first, second);
    }

    @Test
    public void directoryCreatesNestedPath() {
        File dir = LyricsDirectories.directory("nested/path");
        assertNotNull(dir);
        assertTrue(dir.exists());
    }
}
