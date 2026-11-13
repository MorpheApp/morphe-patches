/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.io.File;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class WordTableFileTest {

    private static final byte[] MAGIC = {'T', 'E', 'S', 'T', '0', '0', '0', '0'};

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void openReturnsNullWhenFileNotBuilt() {
        WordTableFile table = new WordTableFile("nonexistent_table.bin", MAGIC, 1 << 10);
        assertNull(table.open());
    }

    @Test
    public void clearDropsCachedTable() {
        WordTableFile table = new WordTableFile("nonexistent_table.bin", MAGIC, 1 << 10);
        assertNull(table.open());
        table.clear();
        assertNull(table.open());
    }

    @Test
    public void fileReturnsNonNullWithBootstrap() {
        WordTableFile table = new WordTableFile("test_table.bin", MAGIC, 1 << 10);
        File file = table.file();
        assertNotNull(file);
    }

    @Test
    public void openAfterClearReturnsNull() {
        WordTableFile table = new WordTableFile("nonexistent_table.bin", MAGIC, 1 << 10);
        table.clear();
        assertNull(table.open());
    }
}
