/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
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

import app.morphe.extension.shared.Logger;

/**
 * The reading of a word, held in a file rather than in the heap. There are hundreds of thousands
 * of them per language and a lyric touches a few hundred, so the table is a hash table on disk
 * and opening it maps the file, leaving the paging to the kernel.
 *
 * <p>A reading is stored as one byte per kana, with an escape for anything outside the katakana
 * block; three bytes per kana would have cost twenty megabytes more.
 * <p>The file layout, all integers little endian:
 * <pre>
 *   0   magic, eight bytes, which say what dictionary wrote the file
 *   8   the dictionary release the table was built from
 *   12  slot count
 *   16  entry count
 *   20  the offset the records start at
 *   24  how many bytes of records there are
 *   32  slots: one int per slot, the offset of a record plus one, or zero when the slot is empty
 *   ..  records: surface length, surface bytes, reading length, reading bytes
 * </pre>
 */
public final class WordReadingTable implements AutoCloseable {

    static final int MAGIC_OFFSET = 0;
    static final int VERSION_OFFSET = 8;
    static final int SLOT_COUNT_OFFSET = 12;
    static final int ENTRY_COUNT_OFFSET = 16;
    static final int RECORDS_OFFSET_FIELD = 20;
    static final int RECORDS_LENGTH_FIELD = 24;
    static final int HEADER_BYTES = 32;

    // Clearing what the largest table asks for, since passing either ceiling drops the entries
    // that no longer fit rather than failing, and they would only be missed later.
    static final int MAX_FILE_BYTES = 128 << 20;
    private static final int MAX_SLOT_COUNT = 1 << 23;

    public static final int MAX_WORD_LENGTH = 12;

    private final MappedByteBuffer buffer;
    private final int recordsOffset;
    private final int entryCount;
    private final int slotMask;

    private WordReadingTable(MappedByteBuffer buffer, int slotCount) {
        this.buffer = buffer;
        this.recordsOffset = buffer.getInt(RECORDS_OFFSET_FIELD);
        this.entryCount = buffer.getInt(ENTRY_COUNT_OFFSET);
        this.slotMask = slotCount - 1;
    }

    /** Null when the file is missing, oversized, or is not a table this build can read. */
    public static WordReadingTable open(Path file, byte[] magic) {
        try {
            long size = Files.size(file);
            if (size < HEADER_BYTES || size > MAX_FILE_BYTES) return null;

            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r");
                 FileChannel channel = handle.getChannel()) {
                MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                mapped.order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < magic.length; i++) {
                    if (mapped.get(MAGIC_OFFSET + i) != magic[i]) return null;
                }
                int slots = mapped.getInt(SLOT_COUNT_OFFSET);
                if (slots <= 0 || slots > MAX_SLOT_COUNT || Integer.bitCount(slots) != 1) return null;
                return new WordReadingTable(mapped, slots);
            }
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not open the reading table " + file, ex);
            return null;
        }
    }

    /** The reading in katakana, empty for a word listed in kana, or null when absent. */
    public String reading(String surface) {
        byte[] query = surface.getBytes(StandardCharsets.UTF_8);
        int slot = hash(query) & slotMask;

        for (int probe = 0; probe <= slotMask; probe++) {
            int offset = buffer.getInt(HEADER_BYTES + (slot << 2));
            if (offset == 0) return null;

            int record = recordsOffset + offset - 1;
            int surfaceLength = buffer.get(record) & 0xFF;
            if (surfaceLength == query.length && matches(record + 1, query, query.length)) {
                int readingLength = buffer.get(record + 1 + surfaceLength) & 0xFF;
                return decode(record + 2 + surfaceLength, readingLength);
            }
            slot = (slot + 1) & slotMask;
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
        private final int maxFileBytes;
        private final int slotMask;
        private int recordsLength;
        private int entryCount;

        public Builder(Path file, byte[] magic, int slotCount) throws IOException {
            this.file = file;
            // Room to spare, since streaming the source does not say how many entries there will be.
            this.maxFileBytes = Math.min(MAX_FILE_BYTES,
                    WordReadingTable.HEADER_BYTES + (slotCount << 2) + (96 << 20));
            Files.deleteIfExists(file);
            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw");
                 FileChannel channel = handle.getChannel()) {
                channel.truncate(maxFileBytes);
                buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, maxFileBytes);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
            }
            slotMask = slotCount - 1;
            recordsOffset = HEADER_BYTES + (slotCount << 2);
            for (int i = 0; i < magic.length; i++) {
                buffer.put(MAGIC_OFFSET + i, magic[i]);
            }
            buffer.putInt(SLOT_COUNT_OFFSET, slotCount);
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
            if (recordsOffset + recordsLength + 2 + key.length + value.length > maxFileBytes) {
                return;
            }

            int slot = hash(key) & slotMask;
            for (int probe = 0; probe <= slotMask; probe++) {
                int offset = buffer.getInt(HEADER_BYTES + (slot << 2));
                if (offset == 0) break;
                int record = recordsOffset + offset - 1;
                int surfaceLength = buffer.get(record) & 0xFF;
                if (surfaceLength == key.length && matches(record + 1, key, key.length)) {
                    return;
                }
                slot = (slot + 1) & slotMask;
            }

            int start = recordsOffset + recordsLength;
            int end = start + 2 + key.length + value.length;
            int at = start;
            buffer.put(at++, (byte) key.length);
            for (byte b : key) {
                buffer.put(at++, b);
            }
            buffer.put(at++, (byte) value.length);
            for (byte b : value) {
                buffer.put(at++, b);
            }
            recordsLength = end - recordsOffset;
            buffer.putInt(HEADER_BYTES + (slot << 2), start - recordsOffset + 1);
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
                    // Low byte first, which is the order a character is read back in.
                    out[length++] = 0;
                    out[length++] = (byte) c;
                    out[length++] = (byte) (c >>> 8);
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
