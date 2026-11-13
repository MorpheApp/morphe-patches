/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class WordReadingTableTest {

    private static final byte[] MAGIC = {'M', 'T', 'E', 'S', 'T', '0', '0', '0'};

    @Test
    public void openReturnsNullForNullPath() {
        assertNull(WordReadingTable.open(null, MAGIC));
    }

    @Test
    public void openReturnsNullForNonexistentFile() throws Exception {
        Path path = Files.createTempFile("nonexistent", ".bin");
        path.toFile().delete();
        assertNull(WordReadingTable.open(path, MAGIC));
    }

    @Test
    public void openReturnsNullForWrongMagic() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("hello", "ハロー");
            builder.finish(1);
        });
        byte[] wrongMagic = {'X', 'Y', 'Z', 'W', '0', '0', '0', '0'};
        assertNull(WordReadingTable.open(path, wrongMagic));
    }

    @Test
    public void builderPutAndReadingFindsEntry() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("hello", "ハロー");
            builder.put("world", "ワールド");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(2, table.size());
            assertEquals("ハロー", table.reading("hello"));
            assertEquals("ワールド", table.reading("world"));
        }
    }

    @Test
    public void readingMissingReturnsNull() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("hello", "ハロー");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertNull(table.reading("missing"));
            assertNull(table.reading(""));
        }
    }

    @Test
    public void sizeReturnsEntryCount() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("one", "一");
            builder.put("two", "二");
            builder.put("three", "三");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(3, table.size());
        }
    }

    @Test
    public void builderFirstReadingWins() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("hello", "ハロー");
            builder.put("hello", "グッダイ"); // Should be ignored
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(1, table.size());
            assertEquals("ハロー", table.reading("hello"));
        }
    }

    @Test
    public void builderSkipsEmptySurface() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("", "reading"); // Should be skipped
            builder.put("valid", "reading");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(1, table.size());
            assertNull(table.reading(""));
        }
    }

    @Test
    public void builderSkipsTooLongSurface() throws Exception {
        String longWord = "a".repeat(WordReadingTable.MAX_WORD_LENGTH + 1);
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put(longWord, "reading"); // Should be skipped
            builder.put("valid", "reading");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(1, table.size());
            assertNull(table.reading(longWord));
        }
    }

    @Test
    public void builderAllowsMaxLengthSurface() throws Exception {
        String maxWord = "a".repeat(WordReadingTable.MAX_WORD_LENGTH);
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put(maxWord, "reading");
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(1, table.size());
            assertEquals("reading", table.reading(maxWord));
        }
    }

    @Test
    public void builderHandlesNonPackableReading() throws Exception {
        // Readings with non-katakana characters use the escape mechanism
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("test", "テス\u0020ト"); // space is not packable
            builder.finish(1);
        });
        try (WordReadingTable table = WordReadingTable.open(path, MAGIC)) {
            assertNotNull(table);
            assertEquals(1, table.size());
            assertNotNull(table.reading("test"));
        }
    }

    @Test
    public void closeDoesNotThrow() throws Exception {
        Path path = buildTable(MAGIC, (builder) -> {
            builder.put("hello", "ハロー");
            builder.finish(1);
        });
        WordReadingTable table = WordReadingTable.open(path, MAGIC);
        assertNotNull(table);
        table.close();
        // close is a no-op for mapped buffers
    }

    @Test
    public void openReturnsNullForEmptyFile() throws Exception {
        File file = File.createTempFile("empty-table", ".bin");
        file.deleteOnExit();
        assertNull(WordReadingTable.open(file.toPath(), MAGIC));
    }

    @FunctionalInterface
    interface BuilderAction {
        void apply(WordReadingTable.Builder builder) throws Exception;
    }

    private static Path buildTable(byte[] magic, BuilderAction action) throws Exception {
        Path path = Files.createTempFile("word-reading-test", ".bin");
        path.toFile().deleteOnExit();
        WordReadingTable.Builder builder = new WordReadingTable.Builder(path, magic, 1 << 10);
        action.apply(builder);
        return path;
    }
}
