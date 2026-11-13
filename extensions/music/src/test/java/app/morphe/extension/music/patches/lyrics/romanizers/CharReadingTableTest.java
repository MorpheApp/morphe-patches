/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CharReadingTableTest {

    @Test
    public void loadAndReadFinding() throws Exception {
        // File must be in code point order for binary search: U+4E00 < U+4E2D < U+597D
        File file = tempFile("一 yi\n中 hanzi\n好 hao\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals(3, table.size());
        assertEquals("hanzi", table.reading('中'));
        assertEquals("yi", table.reading('一'));
        assertEquals("hao", table.reading('好'));
    }

    @Test
    public void readingMissingReturnsNull() throws Exception {
        File file = tempFile("中 hanzi\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertNull(table.reading('日'));
        assertNull(table.reading('A'));
    }

    @Test
    public void readingFirstCharReturnsNull() throws Exception {
        File file = tempFile("中 hanzi\n");
        CharReadingTable table = CharReadingTable.load(file);
        // 'X' (U+0058) is not in the table and is before U+4E2D
        assertNull(table.reading('X'));
    }

    @Test
    public void loadSkipsShortLines() throws Exception {
        // File must be in code point order: U+4E00 < U+4E2D
        File file = tempFile("一 yi\nxy\n中 hanzi\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals(2, table.size());
        assertEquals("hanzi", table.reading('中'));
        assertEquals("yi", table.reading('一'));
    }

    @Test
    public void loadEmptyFileReturnsEmptyTable() throws Exception {
        File file = tempFile("");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals(0, table.size());
        assertNull(table.reading('中'));
    }

    @Test
    public void binarySearchBoundaryFirst() throws Exception {
        File file = tempFile("一 yi\n中 hanzi\n好 hao\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals("yi", table.reading('一'));
    }

    @Test
    public void binarySearchBoundaryLast() throws Exception {
        File file = tempFile("一 yi\n中 hanzi\n好 hao\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals("hao", table.reading('好'));
    }

    @Test
    public void binarySearchMiddle() throws Exception {
        File file = tempFile("一 yi\n中 hanzi\n好 hao\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertEquals("hanzi", table.reading('中'));
    }

    @Test
    public void readingBeforeFirstReturnsNull() throws Exception {
        File file = tempFile("中 hanzi\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertNull(table.reading('一'));
    }

    @Test
    public void readingAfterLastReturnsNull() throws Exception {
        File file = tempFile("中 hanzi\n");
        CharReadingTable table = CharReadingTable.load(file);
        assertNull(table.reading('鿿'));
    }

    private static File tempFile(String content) throws Exception {
        File file = File.createTempFile("char-reading-test", ".txt");
        file.deleteOnExit();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
