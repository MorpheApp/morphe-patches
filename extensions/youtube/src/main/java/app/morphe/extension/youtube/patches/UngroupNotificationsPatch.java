/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
    private static final byte RESPONSE_CONTEXT_TAG = 1 << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED;
    /**
     * Tag byte and the longest length varint.
     */
    private static final int RESPONSE_CONTEXT_HEADER_LENGTH = 6;
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
    @Nullable
    public static byte[] ungroupNotifications(@Nullable byte[] body) {
        try {
            if (body != null && Settings.UNGROUP_NOTIFICATIONS.get() && isInboxResponse(body)) {
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
    @Nullable
    public static Iterable<?> ungroupNotifications(@Nullable Iterable<?> parts) {
        try {
            if (parts == null || !Settings.UNGROUP_NOTIFICATIONS.get()) {
                return parts;
            }
            byte[] header = bytes(parts, RESPONSE_CONTEXT_HEADER_LENGTH);
            int contextEnd = header == null ? 0 : responseContextEnd(header);
            if (contextEnd == 0) {
                return parts;
            }
            byte[] start = bytes(parts, contextEnd);
            if (start == null || !isInboxResponse(start)) {
                return parts;
            }
            byte[] body = bytes(parts, Integer.MAX_VALUE);
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

    /**
     * @param body The response, or its start.
     */
    private static boolean isInboxResponse(byte[] body) {
        int contextEnd = Math.min(responseContextEnd(body), body.length);
        return contextEnd > 0 && INBOX_REQUEST.matches(body, 0, contextEnd);
    }

    /**
     * @param start The start of the response, at least {@link #RESPONSE_CONTEXT_HEADER_LENGTH}
     *              bytes if the response is that long.
     * @return The end of the response context at the start of the response, up to the maximum length,
     *         or 0 if the response doesn't start with it. Can be past the end of the given bytes.
     */
    private static int responseContextEnd(byte[] start) {
        if (start.length == 0 || start[0] != RESPONSE_CONTEXT_TAG) {
            return 0;
        }
        try {
            CodedInputStream input = CodedInputStream.newInstance(start, 1, start.length - 1);
            int contextLength = input.readRawVarint32();
            if (contextLength <= 0) {
                return 0;
            }
            long end = 1L + input.getTotalBytesRead() + contextLength;
            return (int) Math.min(end, MAX_RESPONSE_CONTEXT_LENGTH);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Response does not start with a response context", ex);
            return 0;
        }
    }

    /**
     * @return The first bytes of the parts, up to the length,
     *         or null if a part is not a byte buffer.
     */
    @Nullable
    private static byte[] bytes(Iterable<?> parts, int maxLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (!(part instanceof ByteBuffer)) {
                return null;
            }
            ByteBuffer buffer = ((ByteBuffer) part).duplicate();
            byte[] chunk = new byte[Math.min(buffer.remaining(), maxLength - out.size())];
            buffer.get(chunk);
            out.write(chunk, 0, chunk.length);
            if (out.size() == maxLength) {
                break;
            }
        }
        return out.toByteArray();
    }
}
