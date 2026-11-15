/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Encoded proto fields, to add fields to messages that are kept as bytes.
 */
public final class ProtoBytes {

    public interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }

    public static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    public static byte[] message(int fieldNumber, byte[] payload) throws IOException {
        return encode(output -> output.writeByteArray(fieldNumber, payload));
    }

    public static byte[] string(int fieldNumber, String text) throws IOException {
        return encode(output -> output.writeString(fieldNumber, text));
    }

    public static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
