/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class LyricsFilterDefaultsTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void lyricsCustomRegexSettingNotNull() {
        assertNotNull(LyricsFilterDefaults.LYRICS_CUSTOM_REGEX);
    }

    @Test
    public void lyricsTextFilterSettingNotNull() {
        assertNotNull(LyricsFilterDefaults.LYRICS_TEXT_FILTER);
    }

    @Test
    public void lyricsCreditLineRegexSettingNotNull() {
        assertNotNull(LyricsFilterDefaults.LYRICS_CREDIT_LINE_REGEX);
    }

    @Test
    public void registerIsNoOp() {
        // register() is deliberately empty to avoid null reads during Settings static init.
        LyricsFilterDefaults.register();
    }

    @Test
    public void customRegexHasDefaultValue() {
        String value = LyricsFilterDefaults.LYRICS_CUSTOM_REGEX.get();
        assertNotNull(value);
        assertFalse(value.isEmpty());
    }

    @Test
    public void textFilterHasDefaultValue() {
        String value = LyricsFilterDefaults.LYRICS_TEXT_FILTER.get();
        assertNotNull(value);
    }

    @Test
    public void creditLineRegexHasDefaultValue() {
        String value = LyricsFilterDefaults.LYRICS_CREDIT_LINE_REGEX.get();
        assertNotNull(value);
        assertFalse(value.isEmpty());
    }
}
