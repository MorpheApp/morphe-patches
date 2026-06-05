/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Manual protobuf encoder/decoder that avoids conflicts with YouTube's bundled protobuf version.
 * Implements only the subset of protobuf needed for VOT API communication.
 */
public class VotProtobuf {

    // Wire types
    private static final int WIRETYPE_VARINT = 0;
    private static final int WIRETYPE_64BIT = 1;
    private static final int WIRETYPE_LENGTH_DELIMITED = 2;

    // ==================== ENCODER ====================

    /**
     * Encode a VideoTranslationRequest.
     * Field numbers from yandex.proto:
     *   url = 3 (string)
     *   firstRequest = 5 (bool)
     *   duration = 6 (double)
     *   unknown0 = 7 (int32)
     *   language = 8 (string)
     *   forceSourceLang = 9 (bool)
     *   unknown1 = 10 (int32)
     *   responseLanguage = 14 (string)
     *   unknown2 = 15 (int32)
     *   unknown3 = 16 (int32)
     *   useLivelyVoice = 18 (bool) — live voices from Yandex (more natural TTS)
     *   videoTitle = 19 (string)
     */
    public static byte[] encodeTranslationRequest(
            String url, boolean firstRequest, double duration,
            String language, String responseLanguage, String videoTitle,
            boolean useLiveVoices
    ) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 3, url);
            writeBool(out, 5, firstRequest);
            writeDouble(out, duration);
            writeInt32(out, 7, 1);          // unknown0
            writeString(out, 8, language);
            writeBool(out, 9, false);       // forceSourceLang
            writeInt32(out, 10, 0);         // unknown1
            writeString(out, 14, responseLanguage);
            writeInt32(out, 15, 1);         // unknown2
            writeInt32(out, 16, 2);         // unknown3
            writeBool(out, 18, useLiveVoices);  // useLivelyVoice — live voices
            if (videoTitle != null && !videoTitle.isEmpty()) {
                writeString(out, 19, videoTitle);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode translation request", e);
        }
    }

    /**
     * Encode a VideoTranslationAudioRequest with empty audio.
     *   translationId = 1 (string)
     *   url = 2 (string)
     *   audioInfo = 6 (message): { fileId = 1 (string), audioFile = 2 (bytes) }
     */
    public static byte[] encodeEmptyAudioRequest(String translationId, String url) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 1, translationId);
            writeString(out, 2, url);

            // audioInfo (field 6) is a nested message: AudioBufferObject { fileId=1, audioFile=2 }
            ByteArrayOutputStream audioInfoOut = new ByteArrayOutputStream();
            writeString(audioInfoOut, 1, "web_api_get_all_generating_urls_data_from_iframe");
            // audioFile (field 2) is empty bytes - we skip it

            byte[] audioInfoBytes = audioInfoOut.toByteArray();
            writeBytes(out, audioInfoBytes);

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode audio request", e);
        }
    }

    // ==================== DECODER ====================

    /**
     * Decoded translation response fields.
     */
    public static class TranslationResponse {
        public String url;
        public double duration;
        public int status;
        public int remainingTime = -1;
        public String translationId;
        public String language;
        public String message;
    }

    /**
     * Decode a VideoTranslationResponse:
     *   url = 1 (string), duration = 2 (double), status = 4 (int32),
     *   remainingTime = 5 (int32), translationId = 7 (string),
     *   language = 8 (string), message = 9 (string)
     */
    public static TranslationResponse decodeTranslationResponse(byte[] data) {
        TranslationResponse response = new TranslationResponse();
        int pos = 0;

        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];

            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // url (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.url = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 2: // duration (double)
                    if (wireType == WIRETYPE_64BIT) {
                        response.duration = readDouble(data, pos);
                        pos += 8;
                    }
                    break;
                case 4: // status (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.status = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 5: // remainingTime (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.remainingTime = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                case 7: // translationId (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.translationId = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 8: // language (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.language = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 9: // message (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.message = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }

        return response;
    }

    // ==================== LOW-LEVEL ENCODING ====================

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeRawVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeString(ByteArrayOutputStream out, int fieldNumber, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, fieldNumber, WIRETYPE_LENGTH_DELIMITED);
        writeRawVarint(out, bytes.length);
        out.write(bytes);
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] value) throws IOException {
        writeTag(out, 6, WIRETYPE_LENGTH_DELIMITED);
        writeRawVarint(out, value.length);
        out.write(value);
    }

    private static void writeInt32(ByteArrayOutputStream out, int fieldNumber, int value) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_VARINT);
        writeRawVarint(out, value);
    }

    private static void writeBool(ByteArrayOutputStream out, int fieldNumber, boolean value) throws IOException {
        writeTag(out, fieldNumber, WIRETYPE_VARINT);
        out.write(value ? 1 : 0);
    }

    private static void writeDouble(ByteArrayOutputStream out, double value) throws IOException {
        writeTag(out, 6, WIRETYPE_64BIT);
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(value);
        out.write(buf.array());
    }

    private static void writeRawVarint(ByteArrayOutputStream out, int value) {
        // Handle unsigned encoding for potentially large values
        long unsigned = value & 0xFFFFFFFFL;
        while (unsigned > 0x7F) {
            out.write((int) ((unsigned & 0x7F) | 0x80));
            unsigned >>>= 7;
        }
        out.write((int) unsigned);
    }

    // ==================== LOW-LEVEL DECODING ====================

    /**
     * Read a varint from data at the given position.
     * Returns [value, newPosition].
     */
    private static int[] readVarint(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            pos++;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new int[]{result, pos};
    }

    /**
     * Read a double (8 bytes, little-endian) from data at the given position.
     */
    private static double readDouble(byte[] data, int pos) {
        ByteBuffer buf = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN);
        return buf.getDouble();
    }

    // ==================== SESSION ====================

    /**
     * Encode a YandexSessionRequest.
     *   uuid = 1 (string)
     *   module = 2 (string)
     */
    public static byte[] encodeSessionRequest(String uuid, String module) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, 1, uuid);
            writeString(out, 2, module);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode session request", e);
        }
    }

    /**
     * Decoded session response.
     */
    public static class SessionResponse {
        public String secretKey;
        public int expires;
    }

    /**
     * Decode a YandexSessionResponse.
     *   secretKey = 1 (string)
     *   expires = 2 (int32)
     */
    public static SessionResponse decodeSessionResponse(byte[] data) {
        SessionResponse response = new SessionResponse();
        int pos = 0;

        while (pos < data.length) {
            int[] tagResult = readVarint(data, pos);
            int tag = tagResult[0];
            pos = tagResult[1];

            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // secretKey (string)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        int[] lenResult = readVarint(data, pos);
                        int len = lenResult[0];
                        pos = lenResult[1];
                        response.secretKey = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    }
                    break;
                case 2: // expires (int32)
                    if (wireType == WIRETYPE_VARINT) {
                        int[] valResult = readVarint(data, pos);
                        response.expires = valResult[0];
                        pos = valResult[1];
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }

        return response;
    }

    /**
     * Skip a field of the given wire type. Returns new position.
     */
    private static int skipField(byte[] data, int pos, int wireType) {
        return switch (wireType) {
            case WIRETYPE_VARINT -> {
                while (pos < data.length && (data[pos] & 0x80) != 0) {
                    pos++;
                }
                yield pos + 1;
            }
            case WIRETYPE_64BIT -> pos + 8;
            case WIRETYPE_LENGTH_DELIMITED -> {
                int[] lenResult = readVarint(data, pos);
                yield lenResult[1] + lenResult[0];
            }
            case 5 -> // 32-bit
                    pos + 4;
            default -> data.length; // unknown wire type, skip to end
        };
    }
}
