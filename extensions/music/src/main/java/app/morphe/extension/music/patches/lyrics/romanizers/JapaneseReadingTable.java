/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The reading of a Japanese word, held in a file rather than in the heap. There are well over a
 * million of them and a lyric touches a few hundred, so the table is a hash table on disk and
 * opening it maps the file, leaving the paging to the kernel.
 *
 * <p>A reading is stored as one byte per kana, with an escape for anything outside the katakana
 * block; three bytes per kana would have cost twenty megabytes more.
 * <p>The file layout, all integers little endian:
 * <pre>
 *   0   magic, eight bytes, "MJPW0001"
 *   8   the dictionary release the table was built from
 *   12  slot count
 *   16  entry count
 *   20  the offset the records start at
 *   24  how many bytes of records there are
 *   32  slots: one int per slot, an offset plus one, or zero when the slot is empty
 *   ..  records: surface length, surface bytes, reading length, reading bytes
 * </pre>
 */
public final class JapaneseReadingTable implements AutoCloseable {

    static final byte[] MAGIC = {'M', 'J', 'P', 'W', '0', '0', '0', '1'};

    static final int MAGIC_OFFSET = 0;
    static final int VERSION_OFFSET = 8;
    static final int SLOT_COUNT_OFFSET = 12;
    static final int ENTRY_COUNT_OFFSET = 16;
    static final int RECORDS_OFFSET_FIELD = 20;
    static final int RECORDS_LENGTH_FIELD = 24;
    static final int HEADER_BYTES = 32;

    /** Room to spare, since streaming the source does not say how many entries there
     * will be. */
    static final int SLOT_COUNT = 1 << 22;
    private static final int SLOT_MASK = SLOT_COUNT - 1;

    static final int MAX_FILE_BYTES = 96 << 20;

    public static final int MAX_WORD_LENGTH = 12;

    private final MappedByteBuffer buffer;
    private final int recordsOffset;
    private final int entryCount;

    private JapaneseReadingTable(MappedByteBuffer buffer) {
        this.buffer = buffer;
        this.recordsOffset = buffer.getInt(RECORDS_OFFSET_FIELD);
        this.entryCount = buffer.getInt(ENTRY_COUNT_OFFSET);
    }

    /** Returns null when the file is missing or is not a table this build can read. */
    public static JapaneseReadingTable open(Path file) {
        try {
            long size = Files.size(file);
            if (size < HEADER_BYTES) return null;

            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r");
                 FileChannel channel = handle.getChannel()) {
                MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                mapped.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < MAGIC.length; i++) {
                    if (mapped.get(MAGIC_OFFSET + i) != MAGIC[i]) return null;
                }
                if (mapped.getInt(SLOT_COUNT_OFFSET) != SLOT_COUNT) return null;
                return new JapaneseReadingTable(mapped);
            }
        } catch (IOException ex) {
            return null;
        }
    }

    /** The reading in katakana, empty for a word listed in kana, or null when absent. */
    public String reading(String surface) {
        byte[] query = surface.getBytes(StandardCharsets.UTF_8);
        int slot = hash(query) & SLOT_MASK;

        for (int probe = 0; probe < SLOT_COUNT; probe++) {
            int offset = buffer.getInt(HEADER_BYTES + (slot << 2));
            if (offset == 0) return null;

            int record = recordsOffset + offset - 1;
            int surfaceLength = buffer.get(record) & 0xFF;
            if (surfaceLength == query.length && matches(record + 1, query, query.length)) {
                int readingLength = buffer.get(record + 1 + surfaceLength) & 0xFF;
                return decode(record + 2 + surfaceLength, readingLength);
            }
            slot = (slot + 1) & SLOT_MASK;
        }
        return null;
    }

    private boolean matches(int start, byte[] query, int length) {
        for (int i = 0; i < length; i++) {
            if (buffer.get(start + i) != query[i]) return false;
        }
        return true;
    }

    /** Unpacks a reading, where one byte stands for a kana and an escape stands for a character. */
    private String decode(int start, int length) {
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            byte value = buffer.get(start + i);
            if (value == 0) {
                out.append(buffer.getChar(start + i + 1));
                i += 3;
            } else {
                out.append(KanaRomanizer.unpack(value));
                i++;
            }
        }
        return out.toString();
    }

    public int size() {
        return entryCount;
    }

    @Override
    public void close() {
        // A mapping needs no closing: unmapping by hand would throw where the buffer is
        // still reachable.
    }

    private static int hash(byte[] bytes) {
        int value = 0x811C9DC5;
        for (byte b : bytes) {
            value ^= b & 0xFF;
            value *= 0x01000193;
        }
        return value & 0x7FFFFFFF;
    }

    /** Writes a table. Words go in as they are read, so a dictionary of a few hundred
     * megabytes never has to be held anywhere. */
    public static final class Builder {

        private final Path file;
        private final MappedByteBuffer buffer;
        private final int recordsOffset;
        private int recordsLength;
        private int entryCount;

        public Builder(Path file) throws IOException {
            this.file = file;
            Files.deleteIfExists(file);
            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw");
                 FileChannel channel = handle.getChannel()) {
                channel.truncate(MAX_FILE_BYTES);
                buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, MAX_FILE_BYTES);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
            }
            recordsOffset = HEADER_BYTES + (SLOT_COUNT << 2);
            for (int i = 0; i < MAGIC.length; i++) {
                buffer.put(MAGIC_OFFSET + i, MAGIC[i]);
            }
            buffer.putInt(SLOT_COUNT_OFFSET, SLOT_COUNT);
            buffer.putInt(RECORDS_OFFSET_FIELD, recordsOffset);
        }

        /** The first reading of a surface form wins: the one a listener is likelier to mean. */
        public void put(String surface, String reading) {
            if (surface.isEmpty()) return;
            if (surface.length() > MAX_WORD_LENGTH) return;

            byte[] key = surface.getBytes(StandardCharsets.UTF_8);
            if (key.length == 0 || key.length > 0xFF) return;
            byte[] value = pack(reading);
            if (value.length > 0xFF) return;
            if (recordsOffset + recordsLength + 2 + key.length + value.length > MAX_FILE_BYTES) {
                return;
            }

            int slot = hash(key) & SLOT_MASK;
            for (int probe = 0; probe < SLOT_COUNT; probe++) {
                int offset = buffer.getInt(HEADER_BYTES + (slot << 2));
                if (offset == 0) break;
                int record = recordsOffset + offset - 1;
                int surfaceLength = buffer.get(record) & 0xFF;
                if (surfaceLength == key.length && matches(record + 1, key, key.length)) {
                    return;
                }
                slot = (slot + 1) & SLOT_MASK;
            }

            int at = recordsOffset + recordsLength;
            int end = at + 2 + key.length + value.length;
            buffer.put(at++, (byte) key.length);
            for (byte b : key) {
                buffer.put(at++, b);
            }
            buffer.put(at++, (byte) value.length);
            for (byte b : value) {
                buffer.put(at++, b);
            }
            recordsLength = end - recordsOffset;
            buffer.putInt(HEADER_BYTES + (slot << 2), at - recordsOffset + 1);
            entryCount++;
        }

        private boolean matches(int start, byte[] query, int length) {
            for (int i = 0; i < length; i++) {
                if (buffer.get(start + i) != query[i]) return false;
            }
            return true;
        }

        private static byte[] pack(String reading) {
            byte[] out = new byte[reading.length() * 3];
            int length = 0;
            for (int i = 0; i < reading.length(); i++) {
                char c = reading.charAt(i);
                if (KanaRomanizer.isPackable(c)) {
                    out[length++] = KanaRomanizer.pack(c);
                } else {
                    if (length + 3 > out.length) break;
                    out[length++] = 0;
                    out[length++] = (byte) (c >>> 8);
                    out[length++] = (byte) c;
                }
            }
            byte[] packed = new byte[length];
            System.arraycopy(out, 0, packed, 0, length);
            return packed;
        }

        public int size() {
            return entryCount;
        }

        /** Writes the header and trims the file, which was mapped far larger than it ends. */
        public void finish(int version) throws IOException {
            buffer.putInt(VERSION_OFFSET, version);
            buffer.putInt(ENTRY_COUNT_OFFSET, entryCount);
            buffer.putInt(RECORDS_LENGTH_FIELD, recordsLength);
            buffer.force();

            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
                handle.setLength(recordsOffset + recordsLength);
            }
        }
    }
}
