/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class RomanizationsTest {

    @Test
    public void appendWordToEmptyBuilder() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, "hello");
        assertEquals("hello", out.toString());
    }

    @Test
    public void appendWordAddsSingleSpace() {
        StringBuilder out = new StringBuilder("first");
        Romanizations.appendWord(out, "second");
        assertEquals("first second", out.toString());
    }

    @Test
    public void appendWordMultipleWords() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, "one");
        Romanizations.appendWord(out, "two");
        Romanizations.appendWord(out, "three");
        assertEquals("one two three", out.toString());
    }

    @Test
    public void appendWordIgnoresNull() {
        StringBuilder out = new StringBuilder("keep");
        Romanizations.appendWord(out, null);
        assertEquals("keep", out.toString());
    }

    @Test
    public void appendWordNullFirstThenWord() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, null);
        Romanizations.appendWord(out, "hello");
        assertEquals("hello", out.toString());
    }

    @Test
    public void appendWordIgnoresEmpty() {
        StringBuilder out = new StringBuilder("keep");
        Romanizations.appendWord(out, "");
        assertEquals("keep", out.toString());
    }

    @Test
    public void appendWordNoLeadingSpace() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, "first");
        assertEquals("first", out.toString());
        // The first word must not get a leading space
        assertNotEquals(' ', out.charAt(0));
    }

    @Test
    public void appendWordNullAndEmptySkipped() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, "a");
        Romanizations.appendWord(out, null);
        Romanizations.appendWord(out, "");
        Romanizations.appendWord(out, "b");
        assertEquals("a b", out.toString());
    }

    @Test
    public void appendWordEmptyBuilderNull() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, null);
        assertEquals("", out.toString());
    }

    @Test
    public void appendWordEmptyBuilderEmpty() {
        StringBuilder out = new StringBuilder();
        Romanizations.appendWord(out, "");
        assertEquals("", out.toString());
    }
}
