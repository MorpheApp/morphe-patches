/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5141
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.album;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and rewrites the few protobuf fields of InnerTube messages this patch changes.
 * Every other field is copied byte for byte, so nothing the app reads is lost.
 */
final class AlbumProto {

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    private static final int FIXED64_LENGTH = 8;
    private static final int FIXED32_LENGTH = 4;
    private static final int MAX_VARINT_SHIFT = 64;
    /**
     * A tag and a varint value of 10 bytes each at most.
     */
    private static final int MAX_VARINT_FIELD_LENGTH = 20;
    private static final int VARINT_BITS = 7;
    private static final int VARINT_MASK = 0x7F;
    private static final int VARINT_CONTINUE = 0x80;
    private static final int WIRE_TYPE_BITS = 3;
    private static final int WIRE_TYPE_MASK = 0x7;

    /**
     * Rewrites a message, or returns null to leave it unchanged.
     */
    interface Transform {
        @Nullable
        byte[] apply(byte[] message);
    }

    /**
     * One field of a message, as positions in the bytes of the message.
     */
    static final class Field {
        final int number;
        final int wireType;
        /** Start of the tag of the field. */
        final int start;
        /** Start of the value, after the length for a length delimited field. */
        final int valueStart;
        final int end;
        final long varint;

        private Field(int number, int wireType, int start, int valueStart, int end, long varint) {
            this.number = number;
            this.wireType = wireType;
            this.start = start;
            this.valueStart = valueStart;
            this.end = end;
            this.varint = varint;
        }

        boolean isMessage() {
            return wireType == WIRE_LENGTH_DELIMITED;
        }
    }

    private AlbumProto() {
    }

    /**
     * @throws IllegalArgumentException If the bytes are not a protobuf message.
     */
    static List<Field> parse(byte[] data) {
        List<Field> fields = new ArrayList<>();
        int[] position = {0};
        while (position[0] < data.length) {
            final int start = position[0];
            final long key = readVarint(data, position);
            final int number = (int) (key >>> WIRE_TYPE_BITS);
            final int wireType = (int) (key & WIRE_TYPE_MASK);
            if (number <= 0) throw new IllegalArgumentException("Invalid field number");

            switch (wireType) {
                case WIRE_VARINT -> {
                    final int valueStart = position[0];
                    final long value = readVarint(data, position);
                    fields.add(new Field(number, wireType, start, valueStart, position[0], value));
                }
                case WIRE_FIXED64 -> fields.add(skip(data, position, number, wireType, start, FIXED64_LENGTH));
                case WIRE_FIXED32 -> fields.add(skip(data, position, number, wireType, start, FIXED32_LENGTH));
                case WIRE_LENGTH_DELIMITED -> {
                    final long length = readVarint(data, position);
                    if (length < 0 || length > data.length - position[0]) {
                        throw new IllegalArgumentException("Truncated field");
                    }
                    fields.add(skip(data, position, number, wireType, start, (int) length));
                }
                default -> throw new IllegalArgumentException("Unsupported wire type: " + wireType);
            }
        }
        return fields;
    }

    private static Field skip(byte[] data, int[] position, int number, int wireType, int start, int length) {
        final int valueStart = position[0];
        if (length > data.length - valueStart) throw new IllegalArgumentException("Truncated field");
        position[0] = valueStart + length;
        return new Field(number, wireType, start, valueStart, position[0], 0);
    }

    private static long readVarint(byte[] data, int[] position) {
        long result = 0;
        for (int shift = 0; shift < MAX_VARINT_SHIFT; shift += VARINT_BITS) {
            if (position[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            final int b = data[position[0]++];
            result |= (long) (b & VARINT_MASK) << shift;
            if ((b & VARINT_CONTINUE) == 0) return result;
        }
        throw new IllegalArgumentException("Malformed varint");
    }

    static byte[] value(byte[] data, Field field) {
        byte[] value = new byte[field.end - field.valueStart];
        System.arraycopy(data, field.valueStart, value, 0, value.length);
        return value;
    }

    /**
     * @return The first length delimited field with the number, or null if there is none.
     */
    @Nullable
    static byte[] bytesField(@Nullable byte[] message, int number) {
        if (message == null) return null;
        for (Field field : parse(message)) {
            if (field.number == number && field.isMessage()) return value(message, field);
        }
        return null;
    }

    /**
     * @return The field as text, or an empty string if there is none.
     */
    static String stringField(@Nullable byte[] message, int number) {
        byte[] value = bytesField(message, number);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * @return The first varint field with the number, or the default value if there is none.
     */
    static long varintField(@Nullable byte[] message, int number) {
        if (message == null) return 0;
        for (Field field : parse(message)) {
            if (field.number == number && field.wireType == WIRE_VARINT) return field.varint;
        }
        return 0;
    }

    /**
     * Rewrites every message found at the path, and returns null if none was rewritten.
     * Each element of the path is a field number, and repeated fields are all followed.
     */
    @Nullable
    static byte[] rewritePath(byte[] message, int[] path, int depth, Transform transform) {
        if (depth == path.length) return transform.apply(message);

        List<Field> fields = parse(message);
        ByteArrayOutputStream out = null;
        int copied = 0;
        for (Field field : fields) {
            if (field.number != path[depth] || !field.isMessage()) continue;

            byte[] rewritten = rewritePath(value(message, field), path, depth + 1, transform);
            if (rewritten == null) continue;

            if (out == null) out = new ByteArrayOutputStream(message.length);
            out.write(message, copied, field.start - copied);
            writeBytes(out, field.number, rewritten);
            copied = field.end;
        }
        if (out == null) return null;
        out.write(message, copied, message.length - copied);
        return out.toByteArray();
    }

    static void writeRaw(ByteArrayOutputStream out, byte[] message, Field field) {
        out.write(message, field.start, field.end - field.start);
    }

    static void writeBytes(ByteArrayOutputStream out, int number, byte[] value) {
        writeVarint(out, ((long) number << WIRE_TYPE_BITS) | WIRE_LENGTH_DELIMITED);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    static void writeString(ByteArrayOutputStream out, int number, String value) {
        writeBytes(out, number, value.getBytes(StandardCharsets.UTF_8));
    }

    static void writeVarintField(ByteArrayOutputStream out, int number, long value) {
        writeVarint(out, ((long) number << WIRE_TYPE_BITS) | WIRE_VARINT);
        writeVarint(out, value);
    }

    /**
     * @return The message with the varint field set to the value, in place of any it had.
     */
    static byte[] withVarintField(byte[] message, int number, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + MAX_VARINT_FIELD_LENGTH);
        for (Field field : parse(message)) {
            if (field.number != number) writeRaw(out, message, field);
        }
        writeVarintField(out, number, value);
        return out.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~VARINT_MASK) != 0) {
            out.write((int) ((value & VARINT_MASK) | VARINT_CONTINUE));
            value >>>= VARINT_BITS;
        }
        out.write((int) value);
    }
}
