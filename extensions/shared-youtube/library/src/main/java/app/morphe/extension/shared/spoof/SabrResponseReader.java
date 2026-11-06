/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4316
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Reads a UMP response for its stream protection status and whether it has media. Media parts
 * are skipped without copying them, and parts and headers can be split across any number of chunks.
 */
public final class SabrResponseReader {

    public interface PartListener {
        void onPart(long partType, byte[] part);
    }

    private static final int PART_MEDIA = 21;
    private static final int PART_STREAM_PROTECTION_STATUS = 58;
    private static final int MAX_COPIED_PART_SIZE = 1024;

    @Nullable
    private final PartListener listener;
    private final int[] listenedPartTypes;
    private final byte[] header = new byte[5];
    private int headerLength;
    private long partType = -1;
    private long partRemaining = -1;
    @Nullable
    private byte[] copiedPart;
    private int copiedPartLength;
    private volatile boolean sawMedia;

    public SabrResponseReader() {
        this(null);
    }

    /**
     * @param listener          Given the parts of the listened types.
     * @param listenedPartTypes Types of the parts that are copied and given to the listener.
     */
    public SabrResponseReader(@Nullable PartListener listener, int... listenedPartTypes) {
        this.listener = listener;
        this.listenedPartTypes = listenedPartTypes;
    }

    public boolean sawMedia() {
        return sawMedia;
    }

    private boolean isListenedPart(long type) {
        for (int listenedType : listenedPartTypes) {
            if (listenedType == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The status of the last status part completed in this chunk, or -1 if none was.
     */
    public synchronized int read(ByteBuffer chunk) {
        // A duplicate, so the position native code reads from is not changed.
        ByteBuffer buffer = chunk.duplicate();
        int status = -1;
        while (buffer.hasRemaining()) {
            if (partType < 0 && (partType = readInteger(buffer)) < 0) {
                break;
            }
            if (partRemaining < 0) {
                if ((partRemaining = readInteger(buffer)) < 0) {
                    break;
                }
                if (partType == PART_MEDIA) {
                    sawMedia = true;
                } else if (partType == PART_STREAM_PROTECTION_STATUS || isListenedPart(partType)) {
                    if (partRemaining > MAX_COPIED_PART_SIZE) {
                        throw new IllegalStateException("Part " + partType + " is too large: " + partRemaining);
                    }
                    copiedPart = new byte[(int) partRemaining];
                    copiedPartLength = 0;
                }
            }

            final int count = (int) Math.min(partRemaining, buffer.remaining());
            if (copiedPart != null) {
                buffer.get(copiedPart, copiedPartLength, count);
                copiedPartLength += count;
            } else {
                buffer.position(buffer.position() + count);
            }
            partRemaining -= count;

            if (partRemaining == 0) {
                if (copiedPart != null) {
                    if (partType == PART_STREAM_PROTECTION_STATUS) {
                        status = readStatus(copiedPart);
                    } else if (listener != null) {
                        listener.onPart(partType, copiedPart);
                    }
                    copiedPart = null;
                }
                partType = -1;
                partRemaining = -1;
            }
        }
        return status;
    }

    /**
     * UMP integers: the leading bits of the first byte give the size, not a continuation bit.
     *
     * @return The integer, or -1 if the chunk ended before it.
     */
    private long readInteger(ByteBuffer buffer) {
        if (headerLength == 0) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            header[headerLength++] = buffer.get();
        }
        final int first = header[0] & 0xFF;
        final int size = first < 0x80 ? 1 : first < 0xC0 ? 2 : first < 0xE0 ? 3 : first < 0xF0 ? 4 : 5;
        while (headerLength < size && buffer.hasRemaining()) {
            header[headerLength++] = buffer.get();
        }
        if (headerLength < size) {
            return -1;
        }
        headerLength = 0;

        if (size == 5) {
            // The first byte only gives the size, the value follows in little endian.
            return (header[1] & 0xFFL) | (header[2] & 0xFFL) << 8
                    | (header[3] & 0xFFL) << 16 | (header[4] & 0xFFL) << 24;
        }
        long value = first & (0xFF >> size);
        for (int i = 1; i < size; i++) {
            value |= (header[i] & 0xFFL) << (8 - size + 8 * (i - 1));
        }
        return value;
    }

    /**
     * @return Field 1 of the StreamProtectionStatus message, or -1 if it is missing.
     */
    private static int readStatus(byte[] part) {
        int position = 0;
        while (position < part.length) {
            final int tag = part[position++] & 0xFF;
            if ((tag & 7) != 0) {
                // The message has only varint fields.
                return -1;
            }
            long value = 0;
            for (int shift = 0; position < part.length; shift += 7) {
                final byte b = part[position++];
                value |= (long) (b & 0x7F) << shift;
                if (b >= 0) {
                    break;
                }
            }
            if (tag >> 3 == 1) {
                return (int) value;
            }
        }
        return -1;
    }
}
