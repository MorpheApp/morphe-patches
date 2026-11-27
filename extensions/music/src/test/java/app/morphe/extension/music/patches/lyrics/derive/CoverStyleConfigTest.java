/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.settings.Settings;

public class CoverStyleConfigTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void fromJsonClampsValuesAndRejectsUnknownTokens() {
        CoverStyleConfig config = CoverStyleConfig.fromJson("{"
                + "\"style\":\"not-a-style\","
                + "\"cornerRadiusDp\":99,"
                + "\"shadowDp\":-4,"
                + "\"borderWidthDp\":5,"
                + "\"frameColor\":\"#FF0000\","
                + "\"accentColor\":\"TEAL\","
                + "\"spinSpeed\":9,"
                + "\"titleSizeSp\":99,"
                + "\"perforationCount\":40,"
                + "\"tiltDegrees\":-99}");
        assertEquals("plain", config.style());
        assertEquals(24, config.cornerRadiusDp());
        assertEquals(0, config.shadowDp());
        assertEquals(5, config.borderWidthDp());
        assertEquals(0xFF1A1A1E, CoverStyleConfig.color(config.frameColor(), 0));
        assertEquals(0xFF3E8E8A, CoverStyleConfig.color(config.accentColor(), 0));
        assertEquals(2f, config.spinSpeed(), 0.01f);
        assertEquals(24, config.titleSizeSp());
        assertEquals(12, config.perforationCount());
        assertEquals(-8f, config.tiltDegrees(), 0.01f);
    }

    @Test
    public void stylesKeepTheirOwnSavedParameters() {
        Settings.LYRICS_COVER_STYLE.resetToDefault();
        Settings.LYRICS_COVER_STYLE_PARAMS.resetToDefault();
        CoverStyleConfig vinyl = CoverStyleConfig.defaults("vinyl").withStyle("vinyl");
        CoverStyleConfig film = CoverStyleConfig.defaults("film").withStyle("film");
        CoverStyleConfig.save("vinyl", vinyl);
        CoverStyleConfig.save("film", film);
        assertEquals("vinyl", CoverStyleConfig.load("vinyl").style());
        assertEquals("film", CoverStyleConfig.load("film").style());
        assertTrue(CoverStyleConfig.STYLES.contains("cassette"));
    }
}
