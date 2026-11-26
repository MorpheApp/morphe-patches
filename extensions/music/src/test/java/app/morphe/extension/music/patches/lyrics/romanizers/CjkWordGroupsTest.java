/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class CjkWordGroupsTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void chineseUsesPinyinWords() {
        assertArrayEquals(new int[]{0, 0, 0}, CjkWordGroups.forLine("一个人"));
    }

    @Test
    public void koreanUsesHangulRuns() {
        assertArrayEquals(new int[]{0, 0, -1, 1, 1}, CjkWordGroups.forLine("안녕 세계"));
    }

    @Test
    public void otherScriptsHaveNoGroups() {
        assertNull(CjkWordGroups.forLine("Hello"));
        assertNull(CjkWordGroups.forLine(null));
    }
}
