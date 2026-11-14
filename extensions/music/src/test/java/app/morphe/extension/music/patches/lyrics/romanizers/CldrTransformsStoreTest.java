/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class CldrTransformsStoreTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void arrowFindsRightArrow() throws Exception {
        assertEquals(1, callArrow("a→b"));
    }

    @Test
    public void arrowFindsBidirectionalArrow() throws Exception {
        assertEquals(1, callArrow("a↔b"));
    }

    @Test
    public void arrowReturnsMinusOneWhenNoArrow() throws Exception {
        assertEquals(-1, callArrow("no arrow here"));
    }

    @Test
    public void arrowReturnsMinusOneForEscapedArrow() throws Exception {
        assertEquals(-1, callArrow("\\→"));
    }

    @Test
    public void arrowIgnoresArrowAtStart() throws Exception {
        // Arrow at position 0 has no character before it
        assertEquals(-1, callArrow("→b"));
    }

    @Test
    public void arrowFindsMiddleArrow() throws Exception {
        assertEquals(3, callArrow("abc→def"));
    }

    @Test
    public void leftReturnsBareChar() throws Exception {
        assertEquals("a", callLeft("a→b", 1));
    }

    @Test
    public void leftReturnsTwoChars() throws Exception {
        assertEquals("ab", callLeft("ab→c", 2));
    }

    @Test
    public void leftReturnsQuotedChar() throws Exception {
        assertEquals("a", callLeft("'a'→b", 4));
    }

    @Test
    public void leftReturnsNullForContextual() throws Exception {
        assertNull(callLeft("{a}→b", 4));
    }

    @Test
    public void leftReturnsNullForSet() throws Exception {
        assertNull(callLeft("[a]→b", 4));
    }

    @Test
    public void leftReturnsNullForTooLong() throws Exception {
        assertNull(callLeft("abc→b", 3));
    }

    @Test
    public void leftReturnsNullForEscapedPunctuation() throws Exception {
        assertNull(callLeft("\\;→b", 2));
    }

    @Test
    public void leftReturnsNullForNonLetter() throws Exception {
        assertNull(callLeft("1→b", 1));
    }

    @Test
    public void leftReturnsNullForEmpty() throws Exception {
        assertNull(callLeft("→b", 0));
    }

    @Test
    public void variableNameFindsEquals() throws Exception {
        // Space at index 3 is skipped by continue; '=' is at index 4
        assertEquals(4, callVariableName("$ab = value"));
    }

    @Test
    public void variableNameReturnsMinusOneForNoDollar() throws Exception {
        assertEquals(-1, callVariableName("ab = value"));
    }

    @Test
    public void variableNameReturnsMinusOneForNoEquals() throws Exception {
        assertEquals(-1, callVariableName("$ab value"));
    }

    @Test
    public void variableNameReturnsMinusOneForInvalidChars() throws Exception {
        assertEquals(-1, callVariableName("$a!b = value"));
    }

    @Test
    public void variableNameAllowsUnderscore() throws Exception {
        // Space at index 4 is skipped; '=' is at index 5
        assertEquals(5, callVariableName("$a_b = value"));
    }

    @Test
    public void stripMarksRemovesCombiningDiacritics() throws Exception {
        // Use decomposed form: 'a' + U+0301 (combining acute accent) + 'b'
        assertEquals("ab", callStripMarks("a\u0301b"));
    }

    @Test
    public void stripMarksRemovesSpaces() throws Exception {
        assertEquals("ab", callStripMarks("a b"));
    }

    @Test
    public void stripMarksKeepsLetters() throws Exception {
        assertEquals("abc", callStripMarks("abc"));
    }

    @Test
    public void stripMarksRemovesModifierLetters() throws Exception {
        // U+02B0 is MODIFIER LETTER SMALL H, not a regular letter
        assertEquals("a", callStripMarks("a\u02B0"));
    }

    @Test
    public void unescapeDecodesHexEscape() throws Exception {
        assertEquals("A", callUnescape("\\u0041"));
    }

    @Test
    public void unescapeDecodesMultiCharEscape() throws Exception {
        assertEquals("AB", callUnescape("\\u0041\\u0042"));
    }

    @Test
    public void unescapeReturnsValueWhenNoEscape() throws Exception {
        assertEquals("abc", callUnescape("abc"));
    }

    @Test
    public void unescapeReturnsValueForIncompleteEscape() throws Exception {
        assertEquals("\\u00", callUnescape("\\u00"));
    }

    @Test
    public void expandWithVariable() throws Exception {
        Map<String, String> vars = Map.of("x", "hello");
        assertEquals("hello", callExpand("$x", vars));
    }

    @Test
    public void expandWithoutVariable() throws Exception {
        assertEquals("hello", callExpand("hello", Map.of()));
    }

    @Test
    public void expandReturnsNullForUnknownVariable() throws Exception {
        assertNull(callExpand("$unknown", Map.of()));
    }

    @Test
    public void expandReturnsNullForSet() throws Exception {
        assertNull(callExpand("{set}", Map.of()));
    }

    @Test
    public void expandReturnsNullForPipe() throws Exception {
        assertNull(callExpand("a|b", Map.of()));
    }

    @Test
    public void expandStripsSemicolonContext() throws Exception {
        assertEquals("hello", callExpand("hello;context", Map.of()));
    }

    @Test
    public void expandStripsQuotes() throws Exception {
        assertEquals("hello", callExpand("'hello'", Map.of()));
    }

    @Test
    public void expandDecodesHexEscape() throws Exception {
        assertEquals("A", callExpand("\\u0041", Map.of()));
    }

    @Test
    public void parseRulesExtractsLiteralRule() throws Exception {
        String content = "a→b\n";
        Map<String, String> rules = callParseRules(content, "test");
        assertEquals("b", rules.get("a"));
    }

    @Test
    public void parseRulesSkipsComments() throws Exception {
        String content = "# comment\na→b\n";
        Map<String, String> rules = callParseRules(content, "test");
        assertEquals(1, rules.size());
        assertEquals("b", rules.get("a"));
    }

    @Test
    public void parseRulesSkipsVariableDefinitions() throws Exception {
        // A variable definition without ';' is not stored, so $x is unknown and a -> $x fails to expand.
        String content = "$x = hello\na→$x\n";
        Map<String, String> rules = callParseRules(content, "test");
        assertEquals(0, rules.size());
    }

    @Test
    public void parseRulesSkipsReverseRules() throws Exception {
        // Latin on the left of a reverse arrow is a reverse rule, which is skipped
        String content = "b←a\n";
        Map<String, String> rules = callParseRules(content, "test");
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseRulesSkipsContextualRules() throws Exception {
        String content = "{context}a→b\n";
        Map<String, String> rules = callParseRules(content, "test");
        assertTrue(rules.isEmpty());
    }

    @Test
    public void parseRulesSkipsCapitalLetters() throws Exception {
        // Capital letter rules are skipped; the small letter's rule reads both
        String content = "A→b\na→c\n";
        Map<String, String> rules = callParseRules(content, "test");
        // "A -> b" is skipped (uppercase), but "a -> c" is kept (lowercase)
        assertEquals(1, rules.size());
        assertEquals("c", rules.get("a"));
    }

    @Test
    public void parseRulesAppliesOverrides() throws Exception {
        // The OVERRIDES table has entries for "greek", "cyrillic", etc.
        String content = "β→beta\n";
        Map<String, String> rules = callParseRules(content, "greek");
        // Greek beta is overridden to "v"
        assertEquals("v", rules.get("β"));
    }

    private static int callArrow(String line) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("arrow", String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, line);
    }

    private static String callLeft(String line, int arrow) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("left", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, line, arrow);
    }

    private static int callVariableName(String line) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("variableName", String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, line);
    }

    private static String callStripMarks(String value) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("stripMarks", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static String callUnescape(String value) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("unescape", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static String callExpand(String side, Map<String, String> variables) throws Exception {
        Method method = CldrTransformsStore.class.getDeclaredMethod("expand", String.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(null, side, variables);
    }

    private static Map<String, String> callParseRules(String content, String script) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Method method = CldrTransformsStore.class.getDeclaredMethod("parseRules", byte[].class, String.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(null, bytes, script);
    }
}
