/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class TextTrimTest {

    @Test
    public void stripLeadingDropsLeadingWhitespace() {
        assertEquals("abc", TextTrim.stripLeading("   abc"));
        assertEquals("abc", TextTrim.stripLeading("\t\nabc"));
        assertEquals("", TextTrim.stripLeading("   "));
    }

    @Test
    public void stripLeadingReturnsSameInstanceWhenNothingToStrip() {
        String value = "abc";
        assertSame(value, TextTrim.stripLeading(value));
    }

    @Test
    public void stripTrailingDropsTrailingWhitespace() {
        assertEquals("abc", TextTrim.stripTrailing("abc   "));
        assertEquals("abc", TextTrim.stripTrailing("abc\t\n"));
        assertEquals("", TextTrim.stripTrailing("   "));
    }

    @Test
    public void stripTrailingReturnsSameInstanceWhenNothingToStrip() {
        String value = "abc";
        assertSame(value, TextTrim.stripTrailing(value));
    }

    @Test
    public void spacesProducesNSpaces() {
        assertEquals("", TextTrim.spaces(0));
        assertEquals("", TextTrim.spaces(-1));
        assertEquals(" ", TextTrim.spaces(1));
        assertEquals("    ", TextTrim.spaces(4));
    }

    @Test
    public void mixedWhitespaceOnly() {
        assertEquals("", TextTrim.stripLeading(TextTrim.stripTrailing("  \t")));
    }
}
