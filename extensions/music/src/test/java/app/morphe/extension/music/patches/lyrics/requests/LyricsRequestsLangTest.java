/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.settings.Settings;

/** Unit tests for the language / script resolution methods of {@link LyricsRequests}. Requires {@link LyricsTestEnv#bootstrap()} so Settings can load. */
public class LyricsRequestsLangTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    // ── translationLanguage ──

    @Test
    public void translationLanguageExplicitValue() {
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("ja");
        assertEquals("ja", LyricsRequests.translationLanguage());
    }

    @Test
    public void translationLanguageLowercased() {
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("JA");
        assertEquals("ja", LyricsRequests.translationLanguage());
    }

    // ── romanizationLanguage ──

    @Test
    public void romanizationLanguageExplicitValue() {
        Settings.LYRICS_ROMANIZE_LANGUAGE.save("zh");
        assertEquals("zh", LyricsRequests.romanizationLanguage());
    }

    // ── rubyLanguage ──

    @Test
    public void rubyLanguageExplicitValue() {
        Settings.LYRICS_RUBY_LANGUAGE.save("ja");
        assertEquals("ja", LyricsRequests.rubyLanguage());
    }

    // ── isDefaultRomanizationLanguage ──

    @Test
    public void isDefaultRomanizationLanguageTrue() {
        Settings.LYRICS_ROMANIZE_LANGUAGE.save("DEFAULT");
        assertTrue(LyricsRequests.isDefaultRomanizationLanguage());
    }

    @Test
    public void isDefaultRomanizationLanguageFalse() {
        Settings.LYRICS_ROMANIZE_LANGUAGE.save("en");
        assertFalse(LyricsRequests.isDefaultRomanizationLanguage());
    }

    @Test
    public void isDefaultRomanizationLanguageCaseInsensitive() {
        Settings.LYRICS_ROMANIZE_LANGUAGE.save("default");
        assertTrue(LyricsRequests.isDefaultRomanizationLanguage());
    }

    // ── resolvedScript ──

    @Test
    public void resolvedScriptDeclaredScript() {
        assertEquals("Latn", LyricsRequests.resolvedScript("en-Latn"));
    }

    @Test
    public void resolvedScriptZhTwHant() {
        assertEquals("Hant", LyricsRequests.resolvedScript("zh-TW"));
    }

    @Test
    public void resolvedScriptZhCnHans() {
        assertEquals("Hans", LyricsRequests.resolvedScript("zh-CN"));
    }

    @Test
    public void resolvedScriptZhHkHant() {
        assertEquals("Hant", LyricsRequests.resolvedScript("zh-HK"));
    }

    @Test
    public void resolvedScriptZhSgHans() {
        assertEquals("Hans", LyricsRequests.resolvedScript("zh-SG"));
    }

    @Test
    public void resolvedScriptNonChineseReturnsEmpty() {
        // "ja" has no 4-letter script subtag and is not "zh"
        assertEquals("", LyricsRequests.resolvedScript("ja"));
    }

    @Test
    public void resolvedScriptNullReturnsEmpty() {
        assertEquals("", LyricsRequests.resolvedScript(null));
    }

    @Test
    public void resolvedScriptEmptyReturnsEmpty() {
        assertEquals("", LyricsRequests.resolvedScript(""));
    }

    @Test
    public void resolvedScriptDeclaredScriptLowercase() {
        assertEquals("Latn", LyricsRequests.resolvedScript("en-latn"));
    }

    @Test
    public void resolvedScriptSeparatorUnderscore() {
        // primarySubtag splits only on '-', so "zh_TW" is not recognized as Chinese.
        assertEquals("", LyricsRequests.resolvedScript("zh_TW"));
    }

    // ── isSimplifiedChinese ──

    @Test
    public void isSimplifiedChineseWithZhCn() {
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("zh-CN");
        assertTrue(LyricsRequests.isSimplifiedChinese());
    }

    @Test
    public void isSimplifiedChineseFalseWithZhTw() {
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("zh-TW");
        assertFalse(LyricsRequests.isSimplifiedChinese());
    }

    @Test
    public void isSimplifiedChineseFalseWithNonChinese() {
        Settings.LYRICS_TRANSLATION_LANGUAGE.save("en");
        assertFalse(LyricsRequests.isSimplifiedChinese());
    }

    // ── primarySubtag ──

    @Test
    public void primarySubtagBasic() {
        assertEquals("en", LyricsRequests.primarySubtag("en-US"));
        assertEquals("zh", LyricsRequests.primarySubtag("zh-Hans-CN"));
        assertEquals("ja", LyricsRequests.primarySubtag("ja-JP"));
    }

    @Test
    public void primarySubtagNullReturnsEmpty() {
        assertEquals("", LyricsRequests.primarySubtag(null));
    }

    @Test
    public void primarySubtagNoDash() {
        assertEquals("en", LyricsRequests.primarySubtag("en"));
    }

    @Test
    public void primarySubtagCaseInsensitive() {
        assertEquals("en", LyricsRequests.primarySubtag("EN"));
        assertEquals("zh", LyricsRequests.primarySubtag("ZH-Hans"));
    }
}
