/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class JiebaSegmenterTest {

    private static WordReadingTable table(String[] words, int[] frequencies) throws Exception {
        Path file = Files.createTempFile("jieba-test", ".bin");
        try {
            WordReadingTable.Builder builder =
                    new WordReadingTable.Builder(file, JiebaSegmentStore.MAGIC, 1 << 10);
            for (int i = 0; i < words.length; i++) {
                builder.put(words[i], String.valueOf(frequencies[i]));
            }
            builder.finish(0);
            WordReadingTable table = WordReadingTable.open(file, JiebaSegmentStore.MAGIC);
            assertNotNull(table);
            return table;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void picksTheHighestFrequencyPath() throws Exception {
        WordReadingTable table = table(
                new String[]{"重新", "开始", "重新开", "开", "始"},
                new int[]{3000, 3000, 1, 5, 5});
        assertArrayEquals(new int[]{0, 0, 1, 1},
                JiebaSegmenter.wordGroups("重新开始", table));
    }

    @Test
    public void segmentsAWholeLine() throws Exception {
        WordReadingTable table = table(
                new String[]{"一个", "人", "来了", "一个人"},
                new int[]{2000, 100, 800, 300});
        assertArrayEquals(new int[]{0, 0, 0, 1, 1},
                JiebaSegmenter.wordGroups("一个人来了", table));
    }

    @Test
    public void commonSingleCharactersDoNotSplitAWord() throws Exception {
        WordReadingTable table = table(
                new String[]{"我", "爱", "北", "京", "北京"},
                new int[]{328841, 14000, 17860, 6583, 34488});
        assertArrayEquals(new int[]{0, 1, 2, 2},
                JiebaSegmenter.wordGroups("我爱北京", table));
    }

    @Test
    public void otherScriptsHaveNoGroup() throws Exception {
        WordReadingTable table = table(new String[]{"北京"}, new int[]{34488});
        assertArrayEquals(new int[]{0, 0, -1, -1, -1, 1, 1},
                JiebaSegmenter.wordGroups("北京 OK北京", table));
    }

    @Test
    public void unknownCharactersStandAlone() throws Exception {
        WordReadingTable table = table(new String[]{"你"}, new int[]{1});
        assertArrayEquals(new int[]{0, 1}, JiebaSegmenter.wordGroups("你好", table));
    }

    @Test
    public void emptyLineHasNoGroups() throws Exception {
        WordReadingTable table = table(new String[]{"你"}, new int[]{1});
        assertNull(JiebaSegmenter.wordGroups("", table));
    }
}
