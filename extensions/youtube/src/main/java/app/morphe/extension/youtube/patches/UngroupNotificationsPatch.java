/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.notifications.InboxResponseFlattener;
import app.morphe.extension.youtube.patches.notifications.PlayedVideoTracker;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class UngroupNotificationsPatch {

    /**
     * The inbox response starts with its response context, which names the inbox request.
     */
    private static final ByteTrieSearch INBOX_REQUEST = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes("GetNotificationsInbox"));
    private static final int RESPONSE_CONTEXT_TAG = 1 << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
    private static final int MAX_FIELD_HEADER_LENGTH = 15;
    private static final int MAX_RESPONSE_CONTEXT_LENGTH = 64 * 1024;

    /**
     * Injection point.
     */
    public static void onVideoLoaded(String videoId) {
        try {
            if (Settings.UNGROUP_NOTIFICATIONS.get()) {
                PlayedVideoTracker.onVideoLoaded(videoId);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "onVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point. Raw body of a network response.
     */
    public static byte[] ungroupNotifications(byte[] body) {
        try {
            if (body != null && Settings.UNGROUP_NOTIFICATIONS.get() && isInboxResponse(body, body.length)) {
                return InboxResponseFlattener.flatten(body);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "ungroupNotifications failure", ex);
        }
        return body;
    }

    /**
     * Injection point. Raw body of a network response that arrived in parts.
     */
    public static Iterable<?> ungroupNotifications(Iterable<?> parts) {
        try {
            if (parts == null || !Settings.UNGROUP_NOTIFICATIONS.get()) {
                return parts;
            }
            byte[] header = start(parts, MAX_FIELD_HEADER_LENGTH);
            int contextEnd = header == null ? 0 : responseContextEnd(header, header.length);
            if (contextEnd == 0) {
                return parts;
            }
            byte[] start = start(parts, contextEnd);
            if (start == null || !isInboxResponse(start, start.length)) {
                return parts;
            }
            byte[] body = join(parts);
            if (body == null) {
                return parts;
            }
            byte[] flattened = InboxResponseFlattener.flatten(body);
            if (flattened != body) {
                return List.of(ByteBuffer.wrap(flattened));
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "ungroupNotifications failure", ex);
        }
        return parts;
    }

    private static boolean isInboxResponse(byte[] body, int length) {
        int contextEnd = responseContextEnd(body, length);
        return contextEnd > 0 && INBOX_REQUEST.matches(body, 0, contextEnd);
    }

    /**
     * @return The end of the response context at the start of the response, up to the maximum length
     *         and the available bytes, or 0 if the response doesn't start with it.
     */
    private static int responseContextEnd(byte[] bytes, int length) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(bytes, 0, length);
            if (input.readTag() != RESPONSE_CONTEXT_TAG) {
                return 0;
            }
            int contextLength = input.readRawVarint32();
            if (contextLength <= 0) {
                return 0;
            }
            long end = (long) input.getTotalBytesRead() + contextLength;
            return (int) Math.min(end, Math.min(length, MAX_RESPONSE_CONTEXT_LENGTH));
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * @return The bytes of all parts, or null if a part is not a byte buffer.
     */
    private static byte[] join(Iterable<?> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (!(part instanceof ByteBuffer)) {
                return null;
            }
            ByteBuffer buffer = ((ByteBuffer) part).duplicate();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            out.write(chunk, 0, chunk.length);
        }
        return out.toByteArray();
    }

    /**
     * @return The first bytes of the parts, up to the length, or null if a part is not a byte buffer.
     */
    private static byte[] start(Iterable<?> parts, int length) {
        byte[] start = new byte[length];
        int read = 0;
        for (Object part : parts) {
            if (!(part instanceof ByteBuffer)) {
                return null;
            }
            ByteBuffer buffer = ((ByteBuffer) part).duplicate();
            int count = Math.min(buffer.remaining(), start.length - read);
            buffer.get(start, read, count);
            read += count;
            if (read == start.length) {
                break;
            }
        }
        return read == start.length ? start : Arrays.copyOf(start, read);
    }
}
