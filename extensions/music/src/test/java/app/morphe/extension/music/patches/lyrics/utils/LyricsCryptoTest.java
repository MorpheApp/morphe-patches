/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

public class LyricsCryptoTest {

    private static final String AES_KEY = "0123456789abcdef"; // 16 bytes

    // ── md5Hex / toHex / hexToBytes ──

    @Test
    public void md5HexKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", LyricsCrypto.md5Hex("abc"));
    }

    @Test
    public void md5HexEmptyString() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LyricsCrypto.md5Hex(""));
    }

    @Test
    public void toHexRoundTrips() {
        byte[] data = {0x00, 0x1f, (byte) 0xff, 0x7a, (byte) 0x80};
        assertEquals("001fff7a80", LyricsCrypto.toHex(data));
    }

    @Test
    public void hexToBytesRoundTrips() {
        byte[] data = LyricsCrypto.hexToBytes("001fff7a80");
        assertArrayEquals(new byte[]{0x00, 0x1f, (byte) 0xff, 0x7a, (byte) 0x80}, data);
    }

    @Test
    public void hexToBytesHandlesNullAndOddLength() {
        assertEquals(0, LyricsCrypto.hexToBytes(null).length);
        assertEquals(0, LyricsCrypto.hexToBytes("abc").length); // odd length
    }

    @Test
    public void hexToBytesReturnsEmptyForInvalidChars() {
        assertEquals(0, LyricsCrypto.hexToBytes("zz").length);
    }

    // ── AES ECB round trip ──

    @Test
    public void aesEcbEncryptProducesValidHex() {
        String cipherHex = LyricsCrypto.aesEcbPkcs5EncryptHex("hello world", AES_KEY);
        // ECB with PKCS5 on an 11-byte string pads to 16 bytes -> 32 hex chars.
        assertEquals(32, cipherHex.length());
        // All hex chars.
        assertTrue(cipherHex.matches("[0-9a-f]+"));
    }

    @Test
    public void aesEcbEncryptDifferentInputsDiffer() {
        String a = LyricsCrypto.aesEcbPkcs5EncryptHex("hello world", AES_KEY);
        String b = LyricsCrypto.aesEcbPkcs5EncryptHex("different", AES_KEY);
        assertNotEquals(a, b);
    }

    @Test
    public void aesEcbDecryptInvalidBase64ReturnsEmpty() {
        // android.util.Base64.decode is a stub in unit tests, so invalid input yields empty.
        assertEquals("", LyricsCrypto.aesEcbPkcs5DecryptBase64ToString("!!!not-base64!!!", AES_KEY));
    }

    // ── inflate ──

    @Test
    public void inflateRoundTrip() {
        String plaintext = "The quick brown fox jumps over the lazy dog. 12345!";
        byte[] compressed = deflate(plaintext);
        assertEquals(plaintext, LyricsCrypto.inflate(compressed));
    }

    @Test
    public void inflateEmptyReturnsEmpty() {
        assertEquals("", LyricsCrypto.inflate(new byte[0]));
        assertEquals("", LyricsCrypto.inflate(null));
    }

    @Test
    public void inflateUnicodeRoundTrip() {
        String plaintext = "中文字测试 emoji 🎵";
        byte[] compressed = deflate(plaintext);
        assertEquals(plaintext, LyricsCrypto.inflate(compressed));
    }

    private static byte[] deflate(String text) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(text.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
