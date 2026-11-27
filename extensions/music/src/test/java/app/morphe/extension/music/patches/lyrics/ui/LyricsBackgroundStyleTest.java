/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.BackgroundMode;
import app.morphe.extension.music.settings.Settings;

public class LyricsBackgroundStyleTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void savedModeDefaultsToBlur() {
        Settings.LYRICS_SHARE_BG_MODE.resetToDefault();
        assertEquals(BackgroundMode.BLUR_MESH, LyricsBackgroundStyle.savedMode());
    }

    @Test
    public void saveModeRoundTripsAllFourModes() {
        LyricsBackgroundStyle.saveMode(BackgroundMode.ALBUM_COVER);
        assertEquals(BackgroundMode.ALBUM_COVER, LyricsBackgroundStyle.savedMode());
        LyricsBackgroundStyle.saveMode(BackgroundMode.SOLID_COLOR);
        assertEquals(BackgroundMode.SOLID_COLOR, LyricsBackgroundStyle.savedMode());
        LyricsBackgroundStyle.saveMode(BackgroundMode.CUSTOM_IMAGE);
        assertEquals(BackgroundMode.CUSTOM_IMAGE, LyricsBackgroundStyle.savedMode());
        LyricsBackgroundStyle.saveMode(BackgroundMode.BLUR_MESH);
        assertEquals(BackgroundMode.BLUR_MESH, LyricsBackgroundStyle.savedMode());
    }

    @Test
    public void fullscreenScrimIsSlightlyDeeperThanShareCardScrim() {
        int[] card = LyricsShareCardRenderer.COVER_SCRIM_COLORS;
        int[] fullscreen = LyricsBackgroundStyle.FULLSCREEN_SCRIM_COLORS;
        assertArrayEquals(new int[]{0x8A000000, 0x7A000000, 0xA6000000}, card);
        for (int i = 0; i < card.length; i++) {
            assertTrue((fullscreen[i] >>> 24) > (card[i] >>> 24));
        }
    }

    @Test
    public void textColorSlotResolvesAutoPresetAndCustom() {
        Settings.LYRICS_TEXT_COLOR_SLOT.resetToDefault();
        assertEquals(Integer.valueOf(0), Settings.LYRICS_TEXT_COLOR_SLOT.get());
        assertEquals(0xFFFFFFFF, LyricsTextColor.defaultWhite());
        assertEquals(0xFFFFFFFF, LyricsTextColor.resolve(0, "#00FF00"));
        assertEquals(LyricsTextColor.auto(), LyricsTextColor.resolve(1, "#00FF00"));
        assertEquals(LyricsShareCardRenderer.textColorPreset(2),
                LyricsTextColor.resolve(2, "#00FF00"));
    }

    @Test
    public void cardTextColorIgnoresDefaultAndWhitePreset() {
        Settings.LYRICS_TEXT_COLOR_SLOT.save(0);
        assertNull(LyricsShareDialog.readCardTextColor());
        Settings.LYRICS_TEXT_COLOR_SLOT.save(1);
        assertNull(LyricsShareDialog.readCardTextColor());
        Settings.LYRICS_TEXT_COLOR_SLOT.save(2);
        assertNull(LyricsShareDialog.readCardTextColor());
        Settings.LYRICS_TEXT_COLOR_SLOT.save(3);
        assertEquals(Integer.valueOf(LyricsShareCardRenderer.textColorPreset(3)),
                LyricsShareDialog.readCardTextColor());
        Settings.LYRICS_TEXT_COLOR_SLOT.resetToDefault();
    }

    @Test
    public void solidColorFollowsTheSelectedSlot() {
        int fallback = 0xFF1A1A22;
        LyricsBackgroundStyle.saveColorSlot(0);
        assertEquals(LyricsPaletteCache.albumPalette().backgroundColor(),
                LyricsBackgroundStyle.solidColor(fallback));
        LyricsBackgroundStyle.saveColorSlot(1);
        assertEquals(LyricsShareCardRenderer.solidPresetColor(1),
                LyricsBackgroundStyle.solidColor(fallback));
        LyricsBackgroundStyle.saveColor(0xFF336699);
        LyricsBackgroundStyle.saveColorSlot(-1);
        assertEquals(0xFF336699, LyricsBackgroundStyle.solidColor(fallback));
    }

    @Test
    public void colorRoundTripsIncludingCustom() {
        Settings.LYRICS_SHARE_BG_COLOR.resetToDefault();
        int fallback = 0xFF1A1A22;
        assertEquals(fallback, LyricsBackgroundStyle.savedColor(fallback));
        LyricsBackgroundStyle.saveColor(0xFF336699);
        assertEquals(0xFF336699, LyricsBackgroundStyle.savedColor(fallback));
        LyricsBackgroundStyle.saveColor(0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, LyricsBackgroundStyle.savedColor(fallback));
    }
}
