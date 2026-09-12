/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small crypto helpers shared by the NetEase and QQ lyrics providers.
 *
 * <p>These mirror the encryption the upstream web clients use: NetEase relies on
 * AES-ECB with PKCS5 padding, QQ on triple-DES-ECB with zlib compressed payloads.
 */
final class LyricsCrypto {

    private LyricsCrypto() {
    }

    static String md5Hex(String input) {
        return toHex(md5Bytes(input.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] md5Bytes(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte value : data) {
            builder.append(Character.forDigit((value >> 4) & 0xf, 16));
            builder.append(Character.forDigit(value & 0xf, 16));
        }
        return builder.toString();
    }

    static String aesEcbPkcs5EncryptHex(String data, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return toHex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static String aesEcbPkcs5DecryptBase64ToString(String base64Data, String key) {
        try {
            byte[] raw = Base64.decode(base64Data, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return new String(cipher.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * Decrypts QQ's QRC payloads. These are encrypted with triple-DES (DES-EDE) in ECB mode and
     * then zlib compressed. The upstream web client implements DES by hand and uses a fixed key
     * order (last 8 bytes, middle 8, first 8) that does not match the JCE {@code DESede} key
     * layout, so the cipher is reimplemented here verbatim from the reference client.
     */
    static byte[] tripleDesEcbDecrypt(byte[] data, String key) {
        if (data == null || data.length == 0 || data.length % 8 != 0) {
            return new byte[0];
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int[][] k3 = qrcKeySchedule(Arrays.copyOfRange(keyBytes, 16, 24), true);
        int[][] k2 = qrcKeySchedule(Arrays.copyOfRange(keyBytes, 8, 16), false);
        int[][] k1 = qrcKeySchedule(Arrays.copyOfRange(keyBytes, 0, 8), true);
        int[][][] schedules = {k3, k2, k1};
        byte[] out = new byte[data.length];
        for (int i = 0; i + 8 <= data.length; i += 8) {
            byte[] block = Arrays.copyOfRange(data, i, i + 8);
            for (int k = 0; k < 3; k++) {
                block = qrcCryptBlock(block, schedules[k]);
            }
            System.arraycopy(block, 0, out, i, 8);
        }
        return out;
    }

    private static final int[][] QRC_SBOX = {
            {14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13},
            {15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9},
            {10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12},
            {7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14},
            {2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3},
            {12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13},
            {4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12},
            {13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11}
    };

    private static int qrcBitnum(byte[] bytes, int b, int c) {
        int byteIndex = (b / 32) * 4 + 3 - ((b % 32) / 8);
        if (byteIndex >= bytes.length) {
            return 0;
        }
        return (((bytes[byteIndex] & 0xff) >>> (7 - (b % 8))) & 1) << c;
    }

    private static int qrcBitnumIntr(int value, int b, int c) {
        return (((value >>> (31 - b)) & 1) << c);
    }

    private static int qrcBitnumIntl(int value, int b, int c) {
        return (((value << b) & 0x80000000) >>> c);
    }

    private static int qrcSboxBit(int value) {
        return (value & 32) | ((value & 31) >>> 1) | ((value & 1) << 4);
    }

    private static int[] qrcInitialPermutation(byte[] input) {
        int s0 =
                qrcBitnum(input, 57, 31) | qrcBitnum(input, 49, 30) | qrcBitnum(input, 41, 29) | qrcBitnum(input, 33, 28) | qrcBitnum(input, 25, 27) | qrcBitnum(input, 17, 26) | qrcBitnum(input, 9, 25) | qrcBitnum(input, 1, 24)
                        | qrcBitnum(input, 59, 23) | qrcBitnum(input, 51, 22) | qrcBitnum(input, 43, 21) | qrcBitnum(input, 35, 20) | qrcBitnum(input, 27, 19) | qrcBitnum(input, 19, 18) | qrcBitnum(input, 11, 17) | qrcBitnum(input, 3, 16)
                        | qrcBitnum(input, 61, 15) | qrcBitnum(input, 53, 14) | qrcBitnum(input, 45, 13) | qrcBitnum(input, 37, 12) | qrcBitnum(input, 29, 11) | qrcBitnum(input, 21, 10) | qrcBitnum(input, 13, 9) | qrcBitnum(input, 5, 8)
                        | qrcBitnum(input, 63, 7) | qrcBitnum(input, 55, 6) | qrcBitnum(input, 47, 5) | qrcBitnum(input, 39, 4) | qrcBitnum(input, 31, 3) | qrcBitnum(input, 23, 2) | qrcBitnum(input, 15, 1) | qrcBitnum(input, 7, 0);
        int s1 =
                qrcBitnum(input, 56, 31) | qrcBitnum(input, 48, 30) | qrcBitnum(input, 40, 29) | qrcBitnum(input, 32, 28) | qrcBitnum(input, 24, 27) | qrcBitnum(input, 16, 26) | qrcBitnum(input, 8, 25) | qrcBitnum(input, 0, 24)
                        | qrcBitnum(input, 58, 23) | qrcBitnum(input, 50, 22) | qrcBitnum(input, 42, 21) | qrcBitnum(input, 34, 20) | qrcBitnum(input, 26, 19) | qrcBitnum(input, 18, 18) | qrcBitnum(input, 10, 17) | qrcBitnum(input, 2, 16)
                        | qrcBitnum(input, 60, 15) | qrcBitnum(input, 52, 14) | qrcBitnum(input, 44, 13) | qrcBitnum(input, 36, 12) | qrcBitnum(input, 28, 11) | qrcBitnum(input, 20, 10) | qrcBitnum(input, 12, 9) | qrcBitnum(input, 4, 8)
                        | qrcBitnum(input, 62, 7) | qrcBitnum(input, 54, 6) | qrcBitnum(input, 46, 5) | qrcBitnum(input, 38, 4) | qrcBitnum(input, 30, 3) | qrcBitnum(input, 22, 2) | qrcBitnum(input, 14, 1) | qrcBitnum(input, 6, 0);
        return new int[]{s0, s1};
    }

    private static int[] qrcInversePermutation(int s0, int s1) {
        int[] r = new int[8];
        r[0] = qrcBitnumIntr(s1, 4, 7) | qrcBitnumIntr(s0, 4, 6) | qrcBitnumIntr(s1, 12, 5) | qrcBitnumIntr(s0, 12, 4) | qrcBitnumIntr(s1, 20, 3) | qrcBitnumIntr(s0, 20, 2) | qrcBitnumIntr(s1, 28, 1) | qrcBitnumIntr(s0, 28, 0);
        r[1] = qrcBitnumIntr(s1, 5, 7) | qrcBitnumIntr(s0, 5, 6) | qrcBitnumIntr(s1, 13, 5) | qrcBitnumIntr(s0, 13, 4) | qrcBitnumIntr(s1, 21, 3) | qrcBitnumIntr(s0, 21, 2) | qrcBitnumIntr(s1, 29, 1) | qrcBitnumIntr(s0, 29, 0);
        r[2] = qrcBitnumIntr(s1, 6, 7) | qrcBitnumIntr(s0, 6, 6) | qrcBitnumIntr(s1, 14, 5) | qrcBitnumIntr(s0, 14, 4) | qrcBitnumIntr(s1, 22, 3) | qrcBitnumIntr(s0, 22, 2) | qrcBitnumIntr(s1, 30, 1) | qrcBitnumIntr(s0, 30, 0);
        r[3] = qrcBitnumIntr(s1, 7, 7) | qrcBitnumIntr(s0, 7, 6) | qrcBitnumIntr(s1, 15, 5) | qrcBitnumIntr(s0, 15, 4) | qrcBitnumIntr(s1, 23, 3) | qrcBitnumIntr(s0, 23, 2) | qrcBitnumIntr(s1, 31, 1) | qrcBitnumIntr(s0, 31, 0);
        r[4] = qrcBitnumIntr(s1, 0, 7) | qrcBitnumIntr(s0, 0, 6) | qrcBitnumIntr(s1, 8, 5) | qrcBitnumIntr(s0, 8, 4) | qrcBitnumIntr(s1, 16, 3) | qrcBitnumIntr(s0, 16, 2) | qrcBitnumIntr(s1, 24, 1) | qrcBitnumIntr(s0, 24, 0);
        r[5] = qrcBitnumIntr(s1, 1, 7) | qrcBitnumIntr(s0, 1, 6) | qrcBitnumIntr(s1, 9, 5) | qrcBitnumIntr(s0, 9, 4) | qrcBitnumIntr(s1, 17, 3) | qrcBitnumIntr(s0, 17, 2) | qrcBitnumIntr(s1, 25, 1) | qrcBitnumIntr(s0, 25, 0);
        r[6] = qrcBitnumIntr(s1, 2, 7) | qrcBitnumIntr(s0, 2, 6) | qrcBitnumIntr(s1, 10, 5) | qrcBitnumIntr(s0, 10, 4) | qrcBitnumIntr(s1, 18, 3) | qrcBitnumIntr(s0, 18, 2) | qrcBitnumIntr(s1, 26, 1) | qrcBitnumIntr(s0, 26, 0);
        r[7] = qrcBitnumIntr(s1, 3, 7) | qrcBitnumIntr(s0, 3, 6) | qrcBitnumIntr(s1, 11, 5) | qrcBitnumIntr(s0, 11, 4) | qrcBitnumIntr(s1, 19, 3) | qrcBitnumIntr(s0, 19, 2) | qrcBitnumIntr(s1, 27, 1) | qrcBitnumIntr(s0, 27, 0);
        for (int i = 0; i < 8; i++) {
            r[i] &= 0xff;
        }
        return r;
    }

    private static int qrcDesF(int state, int[] key) {
        int t1 = (qrcBitnumIntl(state, 31, 0) | ((state & 0xf0000000) >>> 1) | qrcBitnumIntl(state, 4, 5) | qrcBitnumIntl(state, 3, 6) | ((state & 0x0f000000) >>> 3) | qrcBitnumIntl(state, 8, 11) | qrcBitnumIntl(state, 7, 12) | ((state & 0x00f00000) >>> 5) | qrcBitnumIntl(state, 12, 17) | qrcBitnumIntl(state, 11, 18) | ((state & 0x000f0000) >>> 7) | qrcBitnumIntl(state, 16, 23));
        int t2 = (qrcBitnumIntl(state, 15, 0) | ((state & 0x0000f000) << 15) | qrcBitnumIntl(state, 20, 5) | qrcBitnumIntl(state, 19, 6) | ((state & 0x00000f00) << 13) | qrcBitnumIntl(state, 24, 11) | qrcBitnumIntl(state, 23, 12) | ((state & 0x000000f0) << 11) | qrcBitnumIntl(state, 28, 17) | qrcBitnumIntl(state, 27, 18) | ((state & 0x0000000f) << 9) | qrcBitnumIntl(state, 0, 23));
        int[] l = new int[6];
        l[0] = ((t1 >>> 24) & 255) ^ key[0];
        l[1] = ((t1 >>> 16) & 255) ^ key[1];
        l[2] = ((t1 >>> 8) & 255) ^ key[2];
        l[3] = ((t2 >>> 24) & 255) ^ key[3];
        l[4] = ((t2 >>> 16) & 255) ^ key[4];
        l[5] = ((t2 >>> 8) & 255) ^ key[5];
        int r = ((QRC_SBOX[0][qrcSboxBit(l[0] >>> 2)] << 28)
                | (QRC_SBOX[1][qrcSboxBit(((l[0] & 3) << 4) | (l[1] >>> 4))] << 24)
                | (QRC_SBOX[2][qrcSboxBit(((l[1] & 15) << 2) | (l[2] >>> 6))] << 20)
                | (QRC_SBOX[3][qrcSboxBit(l[2] & 63)] << 16)
                | (QRC_SBOX[4][qrcSboxBit(l[3] >>> 2)] << 12)
                | (QRC_SBOX[5][qrcSboxBit(((l[3] & 3) << 4) | (l[4] >>> 4))] << 8)
                | (QRC_SBOX[6][qrcSboxBit(((l[4] & 15) << 2) | (l[5] >>> 6))] << 4)
                | QRC_SBOX[7][qrcSboxBit(l[5] & 63)]);
        return (qrcBitnumIntl(r, 15, 0) | qrcBitnumIntl(r, 6, 1) | qrcBitnumIntl(r, 19, 2) | qrcBitnumIntl(r, 20, 3) | qrcBitnumIntl(r, 28, 4) | qrcBitnumIntl(r, 11, 5) | qrcBitnumIntl(r, 27, 6) | qrcBitnumIntl(r, 16, 7) | qrcBitnumIntl(r, 0, 8) | qrcBitnumIntl(r, 14, 9) | qrcBitnumIntl(r, 22, 10) | qrcBitnumIntl(r, 25, 11) | qrcBitnumIntl(r, 4, 12) | qrcBitnumIntl(r, 17, 13) | qrcBitnumIntl(r, 30, 14) | qrcBitnumIntl(r, 9, 15) | qrcBitnumIntl(r, 1, 16) | qrcBitnumIntl(r, 7, 17) | qrcBitnumIntl(r, 23, 18) | qrcBitnumIntl(r, 13, 19) | qrcBitnumIntl(r, 31, 20) | qrcBitnumIntl(r, 26, 21) | qrcBitnumIntl(r, 2, 22) | qrcBitnumIntl(r, 8, 23) | qrcBitnumIntl(r, 18, 24) | qrcBitnumIntl(r, 12, 25) | qrcBitnumIntl(r, 29, 26) | qrcBitnumIntl(r, 5, 27) | qrcBitnumIntl(r, 21, 28) | qrcBitnumIntl(r, 10, 29) | qrcBitnumIntl(r, 3, 30) | qrcBitnumIntl(r, 24, 31));
    }

    private static int[][] qrcKeySchedule(byte[] key, boolean decrypt) {
        int[][] schedule = new int[16][6];
        int[] shifts = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
        int[] pc = {56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35};
        int[] pd = {62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3};
        int[] kc = {13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31};
        int c = 0, d = 0;
        for (int i = 0; i < 28; i++) {
            c = (c + qrcBitnum(key, pc[i], 31 - i));
            d = (d + qrcBitnum(key, pd[i], 31 - i));
        }
        for (int i = 0; i < 16; i++) {
            c = (((c << shifts[i]) | (c >>> (28 - shifts[i]))) & 0xfffffff0);
            d = (((d << shifts[i]) | (d >>> (28 - shifts[i]))) & 0xfffffff0);
            int idx = decrypt ? 15 - i : i;
            for (int j = 0; j < 24; j++) {
                schedule[idx][j / 8] |= qrcBitnumIntr(c, kc[j], 7 - (j % 8));
            }
            for (int j = 24; j < 48; j++) {
                schedule[idx][j / 8] |= qrcBitnumIntr(d, kc[j] - 27, 7 - (j % 8));
            }
        }
        return schedule;
    }

    private static byte[] qrcCryptBlock(byte[] input, int[][] schedule) {
        int[] ip = qrcInitialPermutation(input);
        int s0 = ip[0], s1 = ip[1];
        for (int i = 0; i < 15; i++) {
            int previous = s1;
            s1 = (qrcDesF(s1, schedule[i]) ^ s0);
            s0 = previous;
        }
        s0 = (qrcDesF(s1, schedule[15]) ^ s0);
        int[] inv = qrcInversePermutation(s0, s1);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) inv[i];
        }
        return out;
    }

    /** Inflates a zlib stream (used by both QQ QRC and KuGou KRC payloads). */
    static String inflate(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        int length = hex.length();
        if ((length & 1) != 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
