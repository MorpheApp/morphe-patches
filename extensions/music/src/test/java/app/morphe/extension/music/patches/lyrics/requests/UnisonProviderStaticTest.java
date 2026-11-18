/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for the static helpers of {@link UnisonProvider}: base64Url, toHex, sourceUrl. */
public class UnisonProviderStaticTest {

    // ── sourceUrl ──

    @Test
    public void sourceUrlBuildsUrl() {
        assertEquals("https://unison.boidu.dev/song/abc123",
                UnisonProvider.sourceUrl("abc123"));
    }

    // ── toHex ──

    @Test
    public void toHexEmpty() {
        assertEquals("", UnisonProvider.toHex(new byte[0]));
    }

    @Test
    public void toHexSingleByte() {
        assertEquals("ff", UnisonProvider.toHex(new byte[]{(byte) 0xFF}));
    }

    @Test
    public void toHexZeroByte() {
        assertEquals("00", UnisonProvider.toHex(new byte[]{0}));
    }

    @Test
    public void toHexMultipleBytes() {
        assertEquals("deadbeef", UnisonProvider.toHex(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}));
    }

    @Test
    public void toHexLowercase() {
        assertEquals("0a1b", UnisonProvider.toHex(new byte[]{(byte) 0x0A, (byte) 0x1B}));
    }

    // base64Url uses android.util.Base64 which returns null in unit tests
    // (unitTests.isReturnDefaultValues = true), so it is not testable here.
}
