/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IcuRomanizerTest {

    @Test
    public void romanizeEmptyListReturnsNull() {
        assertNull(IcuRomanizer.romanize(new ArrayList<>()));
    }

    @Test
    public void romanizeCollectionsEmptyListReturnsNull() {
        assertNull(IcuRomanizer.romanize(Collections.emptyList()));
    }

    @Test
    public void romanizeSingleLineReturnsNull() {
        assertNull(IcuRomanizer.romanize(Collections.singletonList("こんにちは")));
    }

    @Test
    public void romanizeMixedContentReturnsNull() {
        List<String> lines = Arrays.asList("你好", "hello", "こんにちは");
        assertNull(IcuRomanizer.romanize(lines));
    }

    @Test
    public void romanizeMultipleLinesReturnsNull() {
        List<String> lines = Arrays.asList("line1", "line2", "line3");
        assertNull(IcuRomanizer.romanize(lines));
    }

    @Test
    public void romanizeRepeatedCallsAllReturnNull() {
        // The static reader stays null under the mockable jar; every call returns null
        assertNull(IcuRomanizer.romanize(Collections.singletonList("test")));
        assertNull(IcuRomanizer.romanize(Collections.singletonList("another")));
        assertNull(IcuRomanizer.romanize(Collections.singletonList("third")));
    }

    @Test
    public void romanizeLargeListReturnsNull() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("line" + i);
        }
        assertNull(IcuRomanizer.romanize(lines));
    }
}
