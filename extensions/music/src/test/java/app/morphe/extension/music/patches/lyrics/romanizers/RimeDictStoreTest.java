/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class RimeDictStoreTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void parseBuiltinNullReturnsEmpty() throws Exception {
        assertTrue(callParseBuiltin(null).isEmpty());
    }

    @Test
    public void parseBuiltinEmptyReturnsEmpty() throws Exception {
        assertTrue(callParseBuiltin("").isEmpty());
    }

    @Test
    public void parseBuiltinWhitespaceReturnsEmpty() throws Exception {
        assertTrue(callParseBuiltin("   ").isEmpty());
    }

    @Test
    public void parseBuiltinSingleCode() throws Exception {
        assertEquals(1, callParseBuiltin("yue").size());
        assertTrue(callParseBuiltin("yue").contains("yue"));
    }

    @Test
    public void parseBuiltinMultipleCodes() throws Exception {
        assertEquals(2, callParseBuiltin("yue, zh").size());
        assertTrue(callParseBuiltin("yue, zh").contains("yue"));
        assertTrue(callParseBuiltin("yue, zh").contains("zh"));
    }

    @Test
    public void parseBuiltinLowercasesCodes() throws Exception {
        assertTrue(callParseBuiltin("YUE").contains("yue"));
    }

    @Test
    public void parseBuiltinSkipsEmptyTokens() throws Exception {
        // "yue,,zh" splits to ["yue", "", "zh"]; empty is skipped, so 2 entries.
        assertEquals(2, callParseBuiltin("yue,,zh").size());
    }

    @Test
    public void isValidCodeTwoLetters() throws Exception {
        assertTrue(callIsValidCode("en"));
    }

    @Test
    public void isValidCodeThreeLetters() throws Exception {
        assertTrue(callIsValidCode("eng"));
    }

    @Test
    public void isValidCodeOneLetterRejected() throws Exception {
        assertFalse(callIsValidCode("e"));
    }

    @Test
    public void isValidCodeFourLettersRejected() throws Exception {
        assertFalse(callIsValidCode("engl"));
    }

    @Test
    public void isValidCodeUppercaseRejected() throws Exception {
        assertFalse(callIsValidCode("EN"));
    }

    @Test
    public void isValidCodeWithDigitRejected() throws Exception {
        assertFalse(callIsValidCode("en1"));
    }

    @Test
    public void isValidCodeWithDashRejected() throws Exception {
        assertFalse(callIsValidCode("en-US"));
    }

    @Test
    public void isSecureHttpsUrl() throws Exception {
        assertTrue(callIsSecure("https://example.com"));
    }

    @Test
    public void isSecureHttpUrlRejected() throws Exception {
        assertFalse(callIsSecure("http://example.com"));
    }

    @Test
    public void isSecureBareHttpsRejected() throws Exception {
        assertFalse(callIsSecure("https://"));
    }

    @Test
    public void isSecureNonUrlRejected() throws Exception {
        assertFalse(callIsSecure("not a url"));
    }

    @Test
    public void isZipTrueForZipUrl() throws Exception {
        assertTrue(callIsZip("https://example.com/file.zip"));
    }

    @Test
    public void isZipTrueForZipUrlWithQuery() throws Exception {
        assertTrue(callIsZip("https://example.com/file.zip?token=abc"));
    }

    @Test
    public void isZipFalseForYamlUrl() throws Exception {
        assertFalse(callIsZip("https://example.com/file.yaml"));
    }

    @Test
    public void isZipFalseForNoExtension() throws Exception {
        assertFalse(callIsZip("https://example.com/file"));
    }

    @Test
    public void isZipTrueForUppercaseZip() throws Exception {
        assertTrue(callIsZip("https://example.com/FILE.ZIP"));
    }

    @Test
    public void archiveNameEndsWithYaml() throws Exception {
        String name = callArchiveName("en", "https://example.com/file.yaml");
        assertTrue(name.endsWith(".yaml"));
        assertTrue(name.contains("en"));
    }

    @Test
    public void archiveNameEndsWithZip() throws Exception {
        String name = callArchiveName("en", "https://example.com/file.zip");
        assertTrue(name.endsWith(".zip"));
        assertTrue(name.contains("en"));
    }

    @Test
    public void tableNameEndsWithBin() throws Exception {
        String name = callTableName("en", "https://example.com/file.yaml");
        assertTrue(name.endsWith(".bin"));
        assertTrue(name.contains("en"));
    }

    @Test
    public void tableNameSameHashAsArchive() throws Exception {
        String url = "https://example.com/file.yaml";
        String archive = callArchiveName("en", url);
        String table = callTableName("en", url);
        String archiveHash = archive.substring(archive.indexOf('_') + 1, archive.lastIndexOf('.'));
        String tableHash = table.substring(table.indexOf('_') + 1, table.lastIndexOf('.'));
        assertEquals(archiveHash, tableHash);
    }

    @Test
    public void magicForReturnsEightBytes() throws Exception {
        byte[] magic = callMagicFor("en");
        assertEquals(8, magic.length);
    }

    @Test
    public void magicForStartsWithMRIME() throws Exception {
        byte[] magic = callMagicFor("en");
        assertEquals('M', (char) magic[0]);
        assertEquals('R', (char) magic[1]);
        assertEquals('I', (char) magic[2]);
        assertEquals('M', (char) magic[3]);
        assertEquals('E', (char) magic[4]);
    }

    @Test
    public void magicForDiffersPerCode() throws Exception {
        byte[] magic1 = callMagicFor("en");
        byte[] magic2 = callMagicFor("zh");
        assertFalse(java.util.Arrays.equals(magic1, magic2));
    }

    @Test
    public void magicForSameCodeSame() throws Exception {
        byte[] magic1 = callMagicFor("en");
        byte[] magic2 = callMagicFor("en");
        assertArrayEquals(magic1, magic2);
    }

    @Test
    public void parseCharSourceCantonese() throws Exception {
        assertEquals(1, callParseCharSource("CANTONESE"));
    }

    @Test
    public void parseCharSourcePinyin() throws Exception {
        assertEquals(2, callParseCharSource("PINYIN"));
    }

    @Test
    public void parseCharSourceNone() throws Exception {
        assertEquals(3, callParseCharSource("NONE"));
    }

    @Test
    public void parseCharSourceAuto() throws Exception {
        assertEquals(0, callParseCharSource("AUTO"));
    }

    @Test
    public void parseCharSourceUnknownReturnsAuto() throws Exception {
        assertEquals(0, callParseCharSource("unknown"));
    }

    @Test
    public void parseCharSourceLowercase() throws Exception {
        assertEquals(1, callParseCharSource("cantonese"));
    }

    @Test
    public void parseCharSourceWithWhitespace() throws Exception {
        assertEquals(1, callParseCharSource("  CANTONESE  "));
    }

    private static java.util.Set<String> callParseBuiltin(String stored) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("parseBuiltin", String.class);
        method.setAccessible(true);
        return (java.util.Set<String>) method.invoke(null, stored);
    }

    private static boolean callIsValidCode(String code) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("isValidCode", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, code);
    }

    private static boolean callIsSecure(String url) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("isSecure", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, url);
    }

    private static boolean callIsZip(String url) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("isZip", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, url);
    }

    private static String callArchiveName(String code, String url) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("archiveName", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, code, url);
    }

    private static String callTableName(String code, String url) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("tableName", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, code, url);
    }

    private static byte[] callMagicFor(String code) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("magicFor", String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, code);
    }

    private static int callParseCharSource(String value) throws Exception {
        Method method = RimeDictStore.class.getDeclaredMethod("parseCharSource", String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, value);
    }
}
