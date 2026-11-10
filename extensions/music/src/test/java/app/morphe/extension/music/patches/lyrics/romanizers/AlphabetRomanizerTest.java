/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Map;

public class AlphabetRomanizerTest {

    /** A Script that contains Latin lowercase and uppercase letters. */
    private static AlphabetRomanizer.Script latinScript() {
        return c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** A Script that contains only 'a' and 'A'. */
    private static AlphabetRomanizer.Script aScript() {
        return c -> c == 'a' || c == 'A';
    }

    @Test
    public void romanizeSingleLetter() {
        Map<String, String> letters = Map.of("a", "ah", "b", "bee");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        assertEquals("ah", r.romanize("a"));
        assertEquals("bee", r.romanize("b"));
    }

    @Test
    public void romanizeDigraph() {
        Map<String, String> letters = Map.of("c", "see", "h", "hah");
        Map<String, String> digraphs = Map.of("ch", "che");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, digraphs, false);
        // The digraph takes priority over the single-letter readings
        assertEquals("che", r.romanize("ch"));
        assertEquals("see", r.romanize("c"));
        assertEquals("hah", r.romanize("h"));
    }

    @Test
    public void romanizeDigraphUppercase() {
        Map<String, String> letters = Map.of("o", "oh", "r", "ar");
        Map<String, String> digraphs = Map.of("or", "orr");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, digraphs, false);
        // A capital source char uppercases the first letter of the reading
        assertEquals("Orr", r.romanize("Or"));
        assertEquals("orr", r.romanize("or"));
    }

    @Test
    public void romanizeDigraphFallbackToLetters() {
        Map<String, String> letters = Map.of("c", "see", "h", "hah");
        // No "ch" digraph → each letter is read on its own
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        assertEquals("seehah", r.romanize("ch"));
    }

    @Test
    public void romanizeCapitalizationPreserved() {
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        assertEquals("Ah", r.romanize("A"));
        assertEquals("ah", r.romanize("a"));
    }

    @Test
    public void romanizeMixedCaseWord() {
        Map<String, String> letters = Map.of("a", "ah", "b", "bee");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        // 'A' is uppercase → "Ah"; 'b' is lowercase → "bee"
        assertEquals("Ahbee", r.romanize("Ab"));
    }

    @Test
    public void romanizePassthroughNonScript() {
        // The script only contains 'a'; everything else passes through whole
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(aScript(), letters, Map.of(), false);
        // Script and non-script runs are separate words, joined with a space.
        assertEquals("ah !", r.romanize("a!"));
        assertEquals("hello", r.romanize("hello"));
    }

    @Test
    public void romanizeMixedScriptAndNonScript() {
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(aScript(), letters, Map.of(), false);
        assertEquals("ah b", r.romanize("a b"));
    }

    @Test
    public void romanizeUnmappedCharPassesThrough() {
        // The script contains all Latin letters, but only 'a' has a reading
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        // 'b' is in the script but not in the letters map → passes through as-is
        assertEquals("b", r.romanize("b"));
        assertEquals("ah", r.romanize("a"));
    }

    @Test
    public void romanizeEmptyString() {
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        assertEquals("", r.romanize(""));
    }

    @Test
    public void romanizeNullThrows() {
        Map<String, String> letters = Map.of("a", "ah");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, Map.of(), false);
        assertThrows(NullPointerException.class, () -> r.romanize(null));
    }

    @Test
    public void romanizeAccentStripping() {
        // 'é' decomposes to 'e' + combining acute; with stripAccents the base is 'e'
        Map<String, String> letters = Map.of("e", "eh");
        AlphabetRomanizer.Script script = c -> c == 'e' || c == 'é';
        AlphabetRomanizer r = new AlphabetRomanizer(script, letters, Map.of(), true);
        assertEquals("eh", r.romanize("é"));
        assertEquals("eh", r.romanize("e"));
    }

    @Test
    public void romanizeAccentNotStripped() {
        // Without stripAccents, 'é' is its own key in the letters map
        Map<String, String> letters = Map.of("e", "eh", "é", "ey");
        AlphabetRomanizer.Script script = c -> c == 'e' || c == 'é';
        AlphabetRomanizer r = new AlphabetRomanizer(script, letters, Map.of(), false);
        assertEquals("ey", r.romanize("é"));
        assertEquals("eh", r.romanize("e"));
    }

    @Test
    public void romanizeDiaeresisBlocksDigraph() {
        // 'ä' decomposes to 'a' + combining diaeresis. With stripAccents the base is 'a',
        // but pairsWith('ä') returns false because 'ä' carries a diaeresis, so 'a' cannot
        // pair with 'ä' as a digraph even though both reduce to 'a'.
        Map<String, String> letters = Map.of("a", "ah");
        Map<String, String> digraphs = Map.of("aa", "ahh");
        AlphabetRomanizer.Script script = c -> c == 'a' || c == 'ä';
        AlphabetRomanizer r = new AlphabetRomanizer(script, letters, digraphs, true);
        // "aa" → digraph matches → "ahh"
        assertEquals("ahh", r.romanize("aa"));
        // "aä" → 'ä' has a diaeresis, so pairsWith('ä') is false → no digraph → each char mapped
        assertEquals("ahah", r.romanize("aä"));
    }

    @Test
    public void sizeReturnsLettersPlusDigraphs() {
        Map<String, String> letters = Map.of("a", "ah", "b", "bee", "c", "see");
        Map<String, String> digraphs = Map.of("ch", "che", "sh", "she");
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), letters, digraphs, false);
        assertEquals(5, r.size());
    }

    @Test
    public void sizeEmptyMaps() {
        AlphabetRomanizer r = new AlphabetRomanizer(latinScript(), Map.of(), Map.of(), false);
        assertEquals(0, r.size());
    }
}
