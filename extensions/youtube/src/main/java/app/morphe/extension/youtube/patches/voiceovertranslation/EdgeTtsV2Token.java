/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3840
 *
 * Adapted to Java from edge-tts 7.3.1 (LGPL-3.0):
 * https://github.com/rany2/edge-tts/blob/7.3.1/src/edge_tts/drm.py
 * Original project copyright: edge-tts contributors.
 * This adaptation is distributed under Morphe's GPL-3.0 license,
 * as permitted for modified versions by LGPL-3.0 section 2(b).
 *
 * See the repository LICENSE and NOTICE files.
 */
package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.nio.charset.StandardCharsets;

/** Generates version-2 Sec-MS-GEC request tokens for Microsoft Edge TTS. */
final class EdgeTtsV2Token {
    private EdgeTtsV2Token() {}

    private static final long WINDOWS_EPOCH_SECONDS = 11_644_473_600L;
    private static final int[] INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };
    private static final int[] PARTIAL_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
    };
    private static final int[] PARTIAL_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
            0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
            0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060, 0x72766564
    };
    private static final int[] K = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4,
            0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE,
            0x9BDC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F,
            0x4A7484AA, 0x5CB0A9DC, 0x76F988DA, 0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC,
            0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B,
            0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070, 0x19A4C116,
            0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7,
            0xC67178F2
    };
    private static final int ADJUSTMENT = 0xC9DC06A7;
    private static final int[][] CS64_COEFF = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D},
            {0x3BEB65BC, 0x29514038}, {0xC89C5933, 0xB328CBDF},
            {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB},
            {0x06064CF3, 0xFA51A3DA}, {0xDA79FA60, 0x207CC464},
            {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}
    };

    /** The input clock is Unix milliseconds; the token is valid for one five-minute window. */
    static String generate(long unixTimestampMillis, String trustedClientToken) {
        long seconds = Math.floorDiv(unixTimestampMillis, 1_000L) + WINDOWS_EPOCH_SECONDS;
        long ticks = Math.floorDiv(seconds, 300L) * 300L * 10_000_000L;
        byte[] data = (ticks + trustedClientToken).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = modifiedSha256(data);
        int[] cs64 = cs64Digest(data, digest);
        return hexLE(cs64[0]) + hexLE(cs64[1]) + "-" + toHex(digest);
    }

    private static int sigma0(int v) {
        return Integer.rotateRight(v, 7) ^ Integer.rotateRight(v, 18) ^ (v >>> 3);
    }
    private static int sigma1(int v) {
        return Integer.rotateRight(v, 17) ^ Integer.rotateRight(v, 19) ^ (v >>> 10);
    }
    private static void expand(int[] w, int first) {
        for (int i = first; i < 64; i++) {
            w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16];
        }
    }
    private static int[] rounds(int[] state, int[] w, int start) {
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = start; i < 64; i++) {
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11)
                    ^ Integer.rotateRight(e, 25);
            int t1 = h + s1 + (g ^ (e & (f ^ g)))
                    + (i == 14 && start == 14 ? ADJUSTMENT : K[i]) + w[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13)
                    ^ Integer.rotateRight(a, 22);
            int t2 = s0 + ((a & b) | (c & (a | b)));
            h = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }
    private static int[] init(byte[] data) {
        int[] w = new int[64];
        System.arraycopy(PARTIAL_SCHEDULE, 0, w, 0, 14);
        w[14] = ((data[0] & 255) << 16) | ((data[1] & 255) << 8) | (data[2] & 255);
        w[15] = bigEndian(data, 3);
        for (int i = 16; i < 30; i++) {
            int value = PARTIAL_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                int x = sigma1(w[i - 2]);
                value += (i == 16 ? x ^ 0x000B92C0 : x);
            }
            if (i - 7 >= 14) value += w[i - 7];
            if (i - 15 >= 14) {
                int x = sigma0(w[i - 15]);
                value += (i == 29 ? x ^ 0x059C0B80 : x);
            }
            w[i] = value;
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14]
                + ADJUSTMENT - K[14];
        expand(w, 31);
        int[] working = rounds(PARTIAL_STATE, w, 14);
        int[] result = new int[8];
        for (int i = 0; i < 8; i++) result[i] = INITIAL[i] + working[i];
        return result;
    }
    private static byte[] modifiedSha256(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("Token data too short");
        long length = 57L + data.length;
        int tail = data.length - 7;
        int zeros = (int) Math.floorMod(55L - length, 64L);
        byte[] padded = new byte[tail + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, tail);
        padded[tail] = (byte) 0x80;
        long bits = length * 8;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));
        }
        int[] state = init(data);
        for (int pos = 0; pos < padded.length; pos += 64) {
            int[] w = new int[64];
            for (int i = 0; i < 16; i++) w[i] = bigEndian(padded, pos + i * 4);
            expand(w, 16);
            int[] working = rounds(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] += working[i];
        }
        byte[] result = new byte[32];
        for (int i = 0; i < 8; i++) {
            result[i * 4] = (byte) (state[i] >>> 24);
            result[i * 4 + 1] = (byte) (state[i] >>> 16);
            result[i * 4 + 2] = (byte) (state[i] >>> 8);
            result[i * 4 + 3] = (byte) state[i];
        }
        return result;
    }
    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = littleEndian(digest, 0) | 1;
        int keyB = littleEndian(digest, 4) | 1;
        int[] factors = {keyA * 0x82893933, keyB * 0x82893933};
        byte[] prefixed = new byte[data.length + 1];
        System.arraycopy(data, 0, prefixed, 1, data.length);
        int wordCount = (prefixed.length + 3) / 4;
        if (wordCount % 2 != 0) wordCount++;
        int total = 14 + wordCount;
        int[] terms = new int[total], offsets = new int[total];
        for (int i = 0; i < 14; i++) {
            terms[i] = CS64_COEFF[i][0] * factors[i % 2];
            offsets[i] = CS64_COEFF[i][1];
        }
        for (int i = 0; i < wordCount; i++) {
            int word = 0;
            for (int j = 0; j < 4; j++) {
                int idx = i * 4 + j;
                if (idx < prefixed.length) word |= (prefixed[idx] & 255) << (8 * j);
            }
            int term = word * (i % 2 == 0 ? keyA : keyB);
            int offset = Integer.rotateLeft(word, 16)
                    * (i % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) {
                term += 0xF2F90B1A * factors[0];
                offset += 0x0B1A0000;
            }
            terms[14 + i] = term;
            offsets[14 + i] = offset;
        }
        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < total; i += 2) {
            int x = terms[i], y = terms[i + 1];
            int t = Integer.rotateLeft(offsets[i] + x, 16);
            int q = t * 0x76F50F34;
            swapAcc += Integer.rotateLeft(q, 16) * 0xC3EFEA97 + q;
            t = Integer.rotateLeft(offsets[i + 1] + y, 16);
            q = t * 0x1881CE3C;
            swapLast = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q;
            swapAcc += swapLast;
            state = state * keyA + x;
            state = Integer.rotateLeft(state, 16) * 0xCF98B111;
            state = Integer.rotateLeft(state, 16) * 0x87085B9F;
            state = Integer.rotateLeft(state, 16) * 0x12CEB96D;
            state = Integer.rotateLeft(state, 16) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = Integer.rotateLeft(t, 16) * 0xA27416F5;
            t = Integer.rotateLeft(t, 16) * 0xD38396FF;
            state = Integer.rotateLeft(Integer.rotateLeft(t, 16) * 0x7C932B89, 16)
                    * 0xBFA49F69;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }
    private static int bigEndian(byte[] data, int off) {
        return ((data[off] & 255) << 24) | ((data[off + 1] & 255) << 16)
                | ((data[off + 2] & 255) << 8) | (data[off + 3] & 255);
    }
    private static int littleEndian(byte[] data, int off) {
        return (data[off] & 255) | ((data[off + 1] & 255) << 8)
                | ((data[off + 2] & 255) << 16) | ((data[off + 3] & 255) << 24);
    }
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 255;
            chars[2 * i] = digits[v >>> 4];
            chars[2 * i + 1] = digits[v & 15];
        }
        return new String(chars);
    }
    private static String hexLE(int v) {
        return toHex(new byte[]{(byte) v, (byte) (v >>> 8),
                (byte) (v >>> 16), (byte) (v >>> 24)});
    }
}
