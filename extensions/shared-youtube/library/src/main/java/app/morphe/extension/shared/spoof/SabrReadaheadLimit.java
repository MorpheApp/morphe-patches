/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.os.SystemClock;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Limits how much media SABR streams buffer ahead of the playback position.
 * <p>
 * The next request policy of each media response tells the player how far ahead to buffer. For the
 * spoofed clients the server keeps raising this target while the video plays, up to about as much
 * media as was already watched. The app keeps all the buffered media in the Java heap, so videos of
 * high quality or at a high speed filled the heap after a few minutes and the app ran out of memory.
 * The target is lowered so the media buffered ahead fits in a part of the heap, using the bitrate of
 * the media that is received, and is never more than the readahead of the player config of the app.
 */
final class SabrReadaheadLimit {
    private static final int PART_MEDIA_HEADER = 20;
    private static final int PART_NEXT_REQUEST_POLICY = 35;

    private static final int MEDIA_HEADER_ITAG_FIELD = 3;
    private static final int MEDIA_HEADER_DURATION_MS_FIELD = 12;
    private static final int MEDIA_HEADER_CONTENT_LENGTH_FIELD = 14;

    private static final int POLICY_TARGET_AUDIO_READAHEAD_MS_FIELD = 1;
    private static final int POLICY_TARGET_VIDEO_READAHEAD_MS_FIELD = 2;

    /**
     * Memory that the media buffered ahead can use: a part of the maximum heap size, up to 100 MB,
     * which is about 30 seconds of 4K video at 60 fps.
     * The media behind the playback position and the rest of the app need the remaining heap.
     */
    private static final long MAX_BUFFERED_BYTES = Math.min(100L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5);
    private static final long MIN_READAHEAD_MS = 10_000;
    /**
     * Max readahead of the player config of the app (exoPlayerConfig maxReadAheadMediaTimeMs).
     * The player configs of the SABR clients have no readahead, so the server target was not limited by time.
     */
    private static final long MAX_READAHEAD_MS = 120_000;

    /**
     * Formats that were not received for this long are no longer counted in the bitrate.
     */
    private static final long FORMAT_EXPIRATION_MS = 30_000;

    /**
     * The bitrate of a format follows the largest segments, and lowers slowly after them.
     */
    private static final double BITRATE_DECAY = 0.9;

    /**
     * Readers of the responses, by the native callbacks of their request.
     */
    private static final Map<Object, SabrResponseReader> responseReaders =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Bytes per millisecond of media of the recently received formats, by itag.
     * The formats of all streams are counted together, which can only lower the limit.
     */
    private static final Map<Integer, FormatBitrate> bitrates = new HashMap<>();

    private static long lastLoggedLimitMs;

    private static final class FormatBitrate {
        double bytesPerMs;
        long receivedTime;
    }

    private SabrReadaheadLimit() {
    }

    /**
     * @param callbacks Native callbacks of a SABR media request, which are also given the response.
     */
    static void onRequest(Object callbacks) {
        responseReaders.put(callbacks, new SabrResponseReader(
                SabrReadaheadLimit::limitReadahead, PART_NEXT_REQUEST_POLICY,
                (partType, part) -> onMediaHeader(part), PART_MEDIA_HEADER));
    }

    static void onResponseChunk(Object callbacks, ByteBuffer chunk) {
        SabrResponseReader reader = responseReaders.get(callbacks);
        if (reader == null) {
            return;
        }
        try {
            reader.read(chunk);
        } catch (Exception ex) {
            responseReaders.remove(callbacks);
            Logger.printInfo(() -> "Could not read media response", ex);
        }
    }

    private static void onMediaHeader(byte[] part) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(part);
            int itag = -1;
            long durationMs = 0;
            long contentLength = 0;
            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case MEDIA_HEADER_ITAG_FIELD -> itag = input.readInt32();
                    case MEDIA_HEADER_DURATION_MS_FIELD -> durationMs = input.readInt64();
                    case MEDIA_HEADER_CONTENT_LENGTH_FIELD -> contentLength = input.readInt64();
                    default -> input.skipField(tag);
                }
            }
            // Init segments have no duration.
            if (itag < 0 || durationMs <= 0 || contentLength <= 0) {
                return;
            }

            final double bytesPerMs = (double) contentLength / durationMs;
            synchronized (bitrates) {
                FormatBitrate bitrate = bitrates.get(itag);
                if (bitrate == null) {
                    bitrate = new FormatBitrate();
                    bitrates.put(itag, bitrate);
                }
                bitrate.bytesPerMs = Math.max(bytesPerMs, bitrate.bytesPerMs * BITRATE_DECAY);
                bitrate.receivedTime = SystemClock.elapsedRealtime();
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read media header", ex);
        }
    }

    /**
     * @return The largest readahead that fits in the heap, or the max readahead if the bitrate is not known yet.
     */
    private static long getReadaheadLimitMs() {
        final long now = SystemClock.elapsedRealtime();
        double bytesPerMs = 0;
        synchronized (bitrates) {
            Iterator<FormatBitrate> iterator = bitrates.values().iterator();
            while (iterator.hasNext()) {
                FormatBitrate bitrate = iterator.next();
                if (now - bitrate.receivedTime > FORMAT_EXPIRATION_MS) {
                    iterator.remove();
                } else {
                    bytesPerMs += bitrate.bytesPerMs;
                }
            }
        }
        if (bytesPerMs <= 0) {
            return MAX_READAHEAD_MS;
        }
        return Math.max(MIN_READAHEAD_MS, Math.min(MAX_READAHEAD_MS, (long) (MAX_BUFFERED_BYTES / bytesPerMs)));
    }

    /**
     * Lowers the readahead targets of a NextRequestPolicy message in place.
     * A target is written with the same number of bytes, so the size of the part does not change.
     * Protobuf varints can have extra empty groups, and parsers accept them.
     */
    private static void limitReadahead(ByteBuffer chunk, int offset, int length) {
        final long limitMs = getReadaheadLimitMs();
        final int end = offset + length;
        int position = offset;
        while (position < end) {
            long tag = 0;
            for (int shift = 0; ; shift += 7) {
                if (position >= end || shift > 28) {
                    return;
                }
                final byte b = chunk.get(position++);
                tag |= (long) (b & 0x7F) << shift;
                if (b >= 0) {
                    break;
                }
            }

            final int fieldNumber = (int) (tag >>> 3);
            switch ((int) (tag & 7)) {
                case WireFormat.WIRETYPE_VARINT -> {
                    final int valueStart = position;
                    long value = 0;
                    for (int shift = 0; ; shift += 7) {
                        if (position >= end || shift > 63) {
                            return;
                        }
                        final byte b = chunk.get(position++);
                        value |= (long) (b & 0x7F) << shift;
                        if (b >= 0) {
                            break;
                        }
                    }
                    if ((fieldNumber == POLICY_TARGET_AUDIO_READAHEAD_MS_FIELD
                            || fieldNumber == POLICY_TARGET_VIDEO_READAHEAD_MS_FIELD) && value > limitMs) {
                        final int valueSize = position - valueStart;
                        long written = limitMs;
                        for (int i = 0; i < valueSize; i++) {
                            final int group = (int) (written & 0x7F);
                            written >>>= 7;
                            chunk.put(valueStart + i, (byte) (i < valueSize - 1 ? group | 0x80 : group));
                        }
                        // The limit follows the bitrate, so it is logged only when it changes much.
                        if (Math.abs(limitMs - lastLoggedLimitMs) > lastLoggedLimitMs / 4) {
                            lastLoggedLimitMs = limitMs;
                            final long serverMs = value;
                            Logger.printDebug(() -> "Limiting SABR readahead from " + serverMs + "ms to " + limitMs + "ms");
                        }
                    }
                }
                case WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    long size = 0;
                    for (int shift = 0; ; shift += 7) {
                        if (position >= end || shift > 28) {
                            return;
                        }
                        final byte b = chunk.get(position++);
                        size |= (long) (b & 0x7F) << shift;
                        if (b >= 0) {
                            break;
                        }
                    }
                    position += (int) size;
                }
                case WireFormat.WIRETYPE_FIXED64 -> position += 8;
                case WireFormat.WIRETYPE_FIXED32 -> position += 4;
                default -> {
                    return;
                }
            }
        }
    }
}
