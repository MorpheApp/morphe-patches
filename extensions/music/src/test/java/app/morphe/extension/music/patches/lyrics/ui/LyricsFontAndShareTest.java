/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsFontAndShareTest {

    private static byte[] buildSyntheticSfnt(
            int sfntMagic,
            int weightClass,
            boolean italic,
            String fullFontName,
            boolean includeVariableAxes) {
        // Build OS/2 table (64 bytes minimum so fsSelection at offset 62 is present)
        ByteBuffer os2 = ByteBuffer.allocate(68).order(ByteOrder.BIG_ENDIAN);
        os2.putShort(0, (short) 4); // version
        os2.putShort(4, (short) weightClass); // usWeightClass
        os2.putShort(62, (short) (italic ? 0x01 : 0x40)); // fsSelection (ITALIC vs REGULAR)
        byte[] os2Bytes = os2.array();

        // Build 'name' table with 1 UTF-16BE Windows record (platformID=3, encodingID=1, nameID=4)
        byte[] strBytes = fullFontName.getBytes(StandardCharsets.UTF_16BE);
        ByteBuffer name = ByteBuffer.allocate(6 + 12 + strBytes.length).order(ByteOrder.BIG_ENDIAN);
        name.putShort((short) 0); // format
        name.putShort((short) 1); // count
        name.putShort((short) (6 + 12)); // stringOffset
        name.putShort((short) 3); // platformID = Windows
        name.putShort((short) 1); // encodingID = Unicode BMP
        name.putShort((short) 0x0409); // languageID = en-US
        name.putShort((short) 4); // nameID = Full font name
        name.putShort((short) strBytes.length);
        name.putShort((short) 0);
        name.put(strBytes);
        byte[] nameBytes = name.array();

        byte[] fvarBytes = null;
        if (includeVariableAxes) {
            // fvar header (16 bytes) + 2 axis records (20 bytes each: 'wght' and 'wdth')
            ByteBuffer fvar = ByteBuffer.allocate(16 + 40).order(ByteOrder.BIG_ENDIAN);
            fvar.putShort((short) 1); // majorVersion
            fvar.putShort((short) 0); // minorVersion
            fvar.putShort((short) 16); // axesArrayOffset
            fvar.putShort((short) 2); // reserved
            fvar.putShort((short) 2); // axisCount = 2
            fvar.putShort((short) 20); // axisSize = 20
            fvar.putShort((short) 0); // instanceCount
            fvar.putShort((short) 0); // instanceSize
            // Axis 0: 'wght'
            fvar.putInt(0x77676874);
            fvar.put(new byte[16]);
            // Axis 1: 'wdth'
            fvar.putInt(0x77647468);
            fvar.put(new byte[16]);
            fvarBytes = fvar.array();
        }

        int numTables = includeVariableAxes ? 3 : 2;
        int headerSize = 12 + numTables * 16;
        int os2Off = headerSize;
        int nameOff = os2Off + os2Bytes.length;
        int fvarOff = nameOff + nameBytes.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer dir = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN);
        dir.putInt(sfntMagic);
        dir.putShort((short) numTables);
        dir.putShort((short) 32);
        dir.putShort((short) 1);
        dir.putShort((short) 0);

        // Record 1: OS/2
        dir.putInt(0x4F532F32);
        dir.putInt(0);
        dir.putInt(os2Off);
        dir.putInt(os2Bytes.length);
        // Record 2: name
        dir.putInt(0x6E616D65);
        dir.putInt(0);
        dir.putInt(nameOff);
        dir.putInt(nameBytes.length);
        if (includeVariableAxes) {
            // Record 3: fvar
            dir.putInt(0x66766172);
            dir.putInt(0);
            dir.putInt(fvarOff);
            dir.putInt(fvarBytes.length);
        }

        out.writeBytes(dir.array());
        out.writeBytes(os2Bytes);
        out.writeBytes(nameBytes);
        if (fvarBytes != null) {
            out.writeBytes(fvarBytes);
        }
        return out.toByteArray();
    }

    @Test
    public void parsesStaticTrueTypeBoldFontMetadata() {
        byte[] sfProBold = buildSyntheticSfnt(0x00010000, 700, false, "SF Pro Bold", false);
        LyricsFontManager.FontMetadata meta = LyricsFontManager.parseFontMetadata(sfProBold);
        assertTrue(meta.valid());
        assertEquals("SF Pro Bold", meta.displayName());
        assertEquals(700, meta.weight());
        assertFalse(meta.italic());
        assertFalse(meta.hasWeightAxis());
        assertFalse(meta.hasWidthAxis());
    }

    @Test
    public void parsesOpenTypeExtraBoldVariableFontMetadata() {
        byte[] interExtraBold = buildSyntheticSfnt(0x4F54544F, 800, false, "Inter ExtraBold", true);
        LyricsFontManager.FontMetadata meta = LyricsFontManager.parseFontMetadata(interExtraBold);
        assertTrue(meta.valid());
        assertEquals("Inter ExtraBold", meta.displayName());
        assertEquals(800, meta.weight());
        assertTrue(meta.hasWeightAxis());
        assertTrue(meta.hasWidthAxis());
    }

    @Test
    public void rejectsNonFontBytes() {
        assertFalse(LyricsFontManager.parseFontMetadata((byte[]) null).valid());
        assertFalse(LyricsFontManager.parseFontMetadata(new byte[]{1, 2, 3, 4}).valid());
        assertFalse(LyricsFontManager.parseFontMetadata(
                "not a font file at all".getBytes(StandardCharsets.UTF_8)).valid());
    }

    @Test
    public void shareSelectionPreselectsActiveLinesAndEnforcesFiveLineLimit() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, 1000, "♪", List.of()),
                new LyricsLine(1000, 2000, "Line one", List.of()),
                new LyricsLine(2000, 3000, "Line two", List.of()),
                new LyricsLine(3000, 4000, "Line three", List.of()),
                new LyricsLine(4000, 5000, "Line four", List.of()),
                new LyricsLine(5000, 6000, "Line five", List.of()),
                new LyricsLine(6000, 7000, "Line six", List.of())
        );

        LyricsShareSelection sel = new LyricsShareSelection();
        // Active set has musical note line 0 plus lines 2 and 3 -> preselects lines 2 and 3.
        sel.preselectActiveLines(lines, Set.of(0, 2, 3), 2, -1);
        assertEquals(2, sel.count());
        assertFalse(sel.isSelected(0));
        assertTrue(sel.isSelected(2));
        assertTrue(sel.isSelected(3));

        // Select up to 5 lines out of order (6, 1, 4)
        assertTrue(sel.toggle(6));
        assertTrue(sel.toggle(1));
        assertTrue(sel.toggle(4));
        assertEquals(5, sel.count());
        assertTrue(sel.isLimitReached());

        // Attempting to select a 6th line (5) is blocked
        assertFalse(sel.toggle(5));
        assertEquals(5, sel.count());

        // Exported lines are always in ascending song order
        assertEquals(
                List.of("Line one", "Line two", "Line three", "Line four", "Line six"),
                sel.selectedLinesInOrder(lines, idx -> lines.get(idx).text()));

        // Deselecting one allows selecting another
        assertTrue(sel.toggle(6));
        assertEquals(4, sel.count());
        assertTrue(sel.toggle(5));
        assertEquals(5, sel.count());
    }

    @Test
    public void shareCardRendererFitsTypographyAndCardGeometry() {
        LyricsShareCardRenderer.CardLayout square = LyricsShareCardRenderer.computeLayout(2);
        assertEquals(340f, square.cardWidth(), 0.01f);
        assertEquals(310f, square.cardHeight(), 0.01f);
        assertEquals(24f, square.padding(), 0.01f);
        assertEquals(48f, square.artworkSize(), 0.01f);

        LyricsShareCardRenderer.CardLayout fiveLine = LyricsShareCardRenderer.computeLayout(5);
        assertTrue(fiveLine.cardHeight() > square.cardHeight());
        assertTrue(fiveLine.lyricsHeight() > square.lyricsHeight());

        // 4 lines -> height = fontSize * 5.0; maxHeight = 90 -> fits ~18sp at scale 1.0
        LyricsShareCardRenderer.TextFit fit = LyricsShareCardRenderer.fitLyricsImageText(
                90f, 1.0f, fontSize -> fontSize * 5.0);
        assertEquals(18f, fit.fontSize(), 0.1f);
        assertEquals(1f, fit.drawScale(), 0.001f);

        // Overflow even at minimum 13sp -> shrinks drawScale smoothly
        LyricsShareCardRenderer.TextFit overflow = LyricsShareCardRenderer.fitLyricsImageText(
                65f, 1.0f, fontSize -> fontSize * 10.0);
        assertEquals(13f, overflow.fontSize(), 0.01f);
        assertEquals(0.5f, overflow.drawScale(), 0.01f);

        List<LyricsShareCardRenderer.CardSwatch> swatches =
                LyricsShareCardRenderer.buildPaletteSwatches(LyricsShareCardRenderer.CardPalette.DEFAULT);
        assertTrue(swatches.size() >= 5);
        assertTrue(swatches.get(0).useBlurredMesh());
        assertFalse(swatches.get(1).useBlurredMesh());
        assertEquals(LyricsShareCardRenderer.BackgroundMode.SOLID_COLOR, swatches.get(1).backgroundMode());

        // Custom flat color picks contrasting text ink automatically
        LyricsShareCardRenderer.CardSwatch bright = LyricsShareCardRenderer.forCustomSolidColor(0xFFFFFFFF);
        assertEquals(0xFF141416, bright.palette().textColor());
        LyricsShareCardRenderer.CardSwatch dark = LyricsShareCardRenderer.forCustomSolidColor(0xFF121218);
        assertEquals(0xFFFFFFFF, dark.palette().textColor());

        // Duet alignment: only split left/right when both sides appear in selection
        List<LyricsShareCardRenderer.ShareLineEntry> mixedDuet = List.of(
                new LyricsShareCardRenderer.ShareLineEntry("Part A", null, null, null, null, false, false),
                new LyricsShareCardRenderer.ShareLineEntry("Part B", null, null, null, null, true, false));
        boolean[] mixedAlign = LyricsShareCardRenderer.resolveEntryRightAlignments(mixedDuet, false);
        assertFalse(mixedAlign[0]);
        assertTrue(mixedAlign[1]);

        List<LyricsShareCardRenderer.ShareLineEntry> singleSideDuet = List.of(
                new LyricsShareCardRenderer.ShareLineEntry("Only B1", null, null, null, null, true, false),
                new LyricsShareCardRenderer.ShareLineEntry("Only B2", null, null, null, null, true, false));
        boolean[] singleAlign = LyricsShareCardRenderer.resolveEntryRightAlignments(singleSideDuet, false);
        assertFalse(singleAlign[0]);
        assertFalse(singleAlign[1]);

        // Branding off trims card height cleanly while still giving lyrics more headroom
        LyricsShareCardRenderer.CardLayout withBrand = LyricsShareCardRenderer.computeLayout(2, 1, true);
        LyricsShareCardRenderer.CardLayout noBrand = LyricsShareCardRenderer.computeLayout(2, 1, false);
        assertTrue(noBrand.cardHeight() < withBrand.cardHeight());
        assertTrue(noBrand.lyricsHeight() > withBrand.lyricsHeight());

        // Copy helper preserves BG vocals, Romaji, Translation cleanly
        String copied = LyricsShareDialog.buildCopyTextForSelection(
                List.of(new LyricsShareCardRenderer.ShareLineEntry(
                        "夢を見て", "Yume wo mite", "Dreaming", "(Ooh)", "(Yeah)", false, false)),
                true, true, true, false, "Song - Artist");
        assertEquals("(Ooh)\n夢を見て\n(Yeah)\nYume wo mite\nDreaming\n\nSong - Artist", copied);

        // Stage backdrop stays deep dark (< 45 channel max) even when card swatch is pure white (#FFFFFF)
        int lightStage = LyricsShareDialog.computeStageBackdropColor(0xFFFFFFFF);
        assertTrue(((lightStage >>> 16) & 0xFF) <= 40);
        assertTrue(((lightStage >>> 8) & 0xFF) <= 40);
        assertTrue((lightStage & 0xFF) <= 45);

        // Card width clamping and ShareSnapshot artwork ownership defaults
        assertEquals(120, LyricsShareCardRenderer.clampCardWidthPx(40));
        assertEquals(900, LyricsShareCardRenderer.clampCardWidthPx(900));
        DynamicBackgroundView.ShareSnapshot unowned = new DynamicBackgroundView.ShareSnapshot(
                null, null, LyricsShareCardRenderer.CardPalette.DEFAULT);
        assertFalse(unowned.ownsArtwork());
        DynamicBackgroundView.ShareSnapshot owned = new DynamicBackgroundView.ShareSnapshot(
                null, true, null, LyricsShareCardRenderer.CardPalette.DEFAULT);
        assertTrue(owned.ownsArtwork());
    }
}
