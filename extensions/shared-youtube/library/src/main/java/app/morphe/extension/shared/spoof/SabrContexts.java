/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * SABR contexts of the web clients.
 * <p>
 * The media responses of a web client can have a context, that the following requests must send back.
 * Until a request has it, the server answers with only a new context and a delay before the next request,
 * and never sends media. The app does not know these contexts, so they are added to its requests here.
 */
final class SabrContexts {
    // UMP parts of media responses.
    static final int PART_SABR_CONTEXT_UPDATE = 57;
    static final int PART_SABR_CONTEXT_SENDING_POLICY = 59;

    // SabrContextUpdate.
    private static final int UPDATE_TYPE_FIELD = 1;
    private static final int UPDATE_VALUE_FIELD = 3;
    private static final int UPDATE_SEND_BY_DEFAULT_FIELD = 4;
    private static final int UPDATE_WRITE_POLICY_FIELD = 5;
    private static final int WRITE_POLICY_KEEP_EXISTING = 2;

    // SabrContextSendingPolicy.
    private static final int SENDING_POLICY_START_FIELD = 1;
    private static final int SENDING_POLICY_STOP_FIELD = 2;
    private static final int SENDING_POLICY_DISCARD_FIELD = 3;

    // StreamerContext.sabrContexts, and the fields of SabrContext.
    static final int STREAMER_CONTEXT_SABR_CONTEXTS_FIELD = 5;
    static final int STREAMER_CONTEXT_UNSENT_SABR_CONTEXTS_FIELD = 6;
    private static final int CONTEXT_TYPE_FIELD = 1;
    private static final int CONTEXT_VALUE_FIELD = 2;

    /**
     * Streams whose contexts are kept. A few, so the contexts of the last videos are not lost
     * while their player is still open.
     */
    private static final int MAX_STREAMS = 5;

    private static final class Context {
        byte[] value;
        boolean send;

        Context(byte[] value, boolean send) {
            this.value = value;
            this.send = send;
        }
    }

    /**
     * Contexts by stream (the 'id' parameter of the stream url), then by type.
     */
    private static final Map<String, Map<Integer, Context>> contextsByStream =
            Utils.createSizeRestrictedMap(MAX_STREAMS, true);

    private SabrContexts() {
    }

    /**
     * @param contexts    SabrContext messages to send.
     * @param unsentTypes Types of the contexts that are kept but not sent, which the web player also tells.
     */
    record ToSend(List<byte[]> contexts, List<Integer> unsentTypes) {
    }

    /**
     * @return The contexts to send with the next request of the stream.
     */
    static synchronized ToSend getContextsToSend(String streamId) throws IOException {
        Map<Integer, Context> contexts = contextsByStream.get(streamId);
        if (contexts == null) {
            return new ToSend(Collections.emptyList(), Collections.emptyList());
        }
        List<byte[]> messages = new ArrayList<>(contexts.size());
        List<Integer> unsentTypes = new ArrayList<>();
        for (Map.Entry<Integer, Context> entry : contexts.entrySet()) {
            Context context = entry.getValue();
            if (!context.send) {
                unsentTypes.add(entry.getKey());
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(context.value.length + 8);
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.writeInt32(CONTEXT_TYPE_FIELD, entry.getKey());
            output.writeByteArray(CONTEXT_VALUE_FIELD, context.value);
            output.flush();
            messages.add(bytes.toByteArray());
        }
        return new ToSend(messages, unsentTypes);
    }

    /**
     * Called with the parts of the media responses of a stream of a web client.
     */
    static synchronized void onPart(String streamId, long partType, byte[] part) {
        try {
            if (partType == PART_SABR_CONTEXT_UPDATE) {
                onContextUpdate(streamId, part);
            } else if (partType == PART_SABR_CONTEXT_SENDING_POLICY) {
                onSendingPolicy(streamId, part);
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read SABR context part: " + partType, ex);
        }
    }

    private static void onContextUpdate(String streamId, byte[] part) throws IOException {
        int type = -1;
        byte[] value = null;
        boolean sendByDefault = false;
        int writePolicy = 0;

        CodedInputStream input = CodedInputStream.newInstance(part);
        for (int tag; (tag = input.readTag()) != 0; ) {
            final int field = WireFormat.getTagFieldNumber(tag);
            final int wireType = WireFormat.getTagWireType(tag);
            if (field == UPDATE_TYPE_FIELD && wireType == WireFormat.WIRETYPE_VARINT) {
                type = input.readInt32();
            } else if (field == UPDATE_VALUE_FIELD && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                value = input.readByteArray();
            } else if (field == UPDATE_SEND_BY_DEFAULT_FIELD && wireType == WireFormat.WIRETYPE_VARINT) {
                sendByDefault = input.readBool();
            } else if (field == UPDATE_WRITE_POLICY_FIELD && wireType == WireFormat.WIRETYPE_VARINT) {
                writePolicy = input.readInt32();
            } else {
                input.skipField(tag);
            }
        }
        if (type < 0 || value == null) {
            return;
        }

        Map<Integer, Context> contexts = contextsByStream.get(streamId);
        if (contexts == null) {
            contexts = new LinkedHashMap<>();
            contextsByStream.put(streamId, contexts);
        }
        Context existing = contexts.get(type);
        if (existing != null && writePolicy == WRITE_POLICY_KEEP_EXISTING) {
            return;
        }
        if (existing != null) {
            existing.value = value;
        } else {
            contexts.put(type, new Context(value, sendByDefault));
        }
        final int updatedType = type;
        Logger.printDebug(() -> "SABR context updated: " + updatedType);
    }

    private static void onSendingPolicy(String streamId, byte[] part) throws IOException {
        Map<Integer, Context> contexts = contextsByStream.get(streamId);
        if (contexts == null) {
            return;
        }
        CodedInputStream input = CodedInputStream.newInstance(part);
        for (int tag; (tag = input.readTag()) != 0; ) {
            final int field = WireFormat.getTagFieldNumber(tag);
            if (field != SENDING_POLICY_START_FIELD && field != SENDING_POLICY_STOP_FIELD
                    && field != SENDING_POLICY_DISCARD_FIELD) {
                input.skipField(tag);
                continue;
            }
            // Repeated int32, packed or not.
            List<Integer> types = new ArrayList<>();
            if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                final int limit = input.pushLimit(input.readRawVarint32());
                while (input.getBytesUntilLimit() > 0) {
                    types.add(input.readInt32());
                }
                input.popLimit(limit);
            } else {
                types.add(input.readInt32());
            }
            for (int type : types) {
                if (field == SENDING_POLICY_DISCARD_FIELD) {
                    contexts.remove(type);
                } else {
                    Context context = contexts.get(type);
                    if (context != null) {
                        context.send = field == SENDING_POLICY_START_FIELD;
                    }
                }
            }
        }
    }
}
