/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_CLIENT_NAME_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_CLIENT_VERSION_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_DEVICE_MAKE_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_DEVICE_MODEL_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_GL_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_HL_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_OS_NAME_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_OS_VERSION_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.CLIENT_INFO_VISITOR_DATA_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.REQUEST_STREAMER_CONTEXT_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.STREAMER_CONTEXT_CLIENT_INFO_FIELD;
import static app.morphe.extension.shared.spoof.SabrRequestBody.STREAMER_CONTEXT_PO_TOKEN_FIELD;

import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Debug logging of SABR media requests and responses while spoofing.
 * Logs which client the requests name, and the parts of the responses that are not media.
 */
final class SabrMediaLogger {
    // UMP parts of media responses that are logged.
    private static final int PART_SABR_ERROR = 44;
    private static final int PART_RELOAD_PLAYER_RESPONSE = 46;
    private static final int PART_PLAYBACK_START_POLICY = 47;
    private static final int PART_SNACKBAR_MESSAGE = 67;

    private static final int STREAM_PROTECTION_STATUS_OK = 1;

    /**
     * Readers of the responses, by the native callbacks of their request.
     */
    private static final Map<Object, SabrResponseReader> responseReaders =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Media requests repeat every few seconds, so a request is logged only if it differs from the last one.
     */
    @Nullable
    private static String lastLoggedRequest;

    private static final Map<Long, String> lastLoggedControlParts = new HashMap<>();

    private SabrMediaLogger() {
    }

    static boolean isEnabled() {
        return Logger.isDebugEnabled();
    }

    /**
     * @param urlClient Client the stream url is for.
     * @param body      The request body that is sent.
     * @param callbacks Native callbacks of the request, which are also given the response.
     */
    static void onRequest(@Nullable String urlClient, @Nullable byte[] body, Object callbacks) {
        logRequest(urlClient, body);
        responseReaders.put(callbacks, new SabrResponseReader(SabrMediaLogger::logControlPart,
                PART_SABR_ERROR, PART_RELOAD_PLAYER_RESPONSE, PART_PLAYBACK_START_POLICY, PART_SNACKBAR_MESSAGE));
    }

    static void onResponseChunk(Object callbacks, ByteBuffer chunk) {
        if (responseReaders.isEmpty()) {
            return;
        }
        SabrResponseReader reader = responseReaders.get(callbacks);
        if (reader == null) {
            return;
        }
        try {
            final int status = reader.read(chunk);
            if (status >= 0 && status != STREAM_PROTECTION_STATUS_OK) {
                Logger.printDebug(() -> "SABR stream protection status: " + status);
            }
        } catch (Exception ex) {
            responseReaders.remove(callbacks);
            Logger.printInfo(() -> "Could not read media response", ex);
        }
    }

    private static synchronized void logRequest(@Nullable String urlClient, @Nullable byte[] body) {
        try {
            StringBuilder builder = new StringBuilder("url client: ").append(urlClient);

            byte[] streamerContext = body == null
                    ? null
                    : SabrRequestBody.readMessageField(body, REQUEST_STREAMER_CONTEXT_FIELD);
            if (streamerContext == null) {
                builder.append(", no streamer context");
            } else {
                byte[] clientInfo = SabrRequestBody.readMessageField(streamerContext, STREAMER_CONTEXT_CLIENT_INFO_FIELD);
                if (clientInfo == null) {
                    builder.append(", no client info");
                } else {
                    appendClientInfo(builder, clientInfo);
                }
                byte[] poToken = SabrRequestBody.readMessageField(streamerContext, STREAMER_CONTEXT_PO_TOKEN_FIELD);
                builder.append(", poToken bytes: ").append(poToken == null ? 0 : poToken.length);
            }

            String message = builder.toString();
            if (!message.equals(lastLoggedRequest)) {
                lastLoggedRequest = message;
                Logger.printDebug(() -> "SABR request " + message);
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read SABR request", ex);
        }
    }

    @Nullable
    private static String getClientInfoFieldName(int field) {
        return switch (field) {
            case CLIENT_INFO_DEVICE_MAKE_FIELD -> "deviceMake";
            case CLIENT_INFO_DEVICE_MODEL_FIELD -> "deviceModel";
            case CLIENT_INFO_CLIENT_NAME_FIELD -> "clientName";
            case CLIENT_INFO_CLIENT_VERSION_FIELD -> "clientVersion";
            case CLIENT_INFO_OS_NAME_FIELD -> "osName";
            case CLIENT_INFO_OS_VERSION_FIELD -> "osVersion";
            case CLIENT_INFO_HL_FIELD -> "hl";
            case CLIENT_INFO_GL_FIELD -> "gl";
            default -> null;
        };
    }

    private static void appendClientInfo(StringBuilder builder, byte[] clientInfo) throws IOException {
        boolean hasVisitorData = false;
        CodedInputStream input = CodedInputStream.newInstance(clientInfo);
        for (int tag; (tag = input.readTag()) != 0; ) {
            final int field = WireFormat.getTagFieldNumber(tag);
            final int wireType = WireFormat.getTagWireType(tag);
            String name = getClientInfoFieldName(field);

            if (field == CLIENT_INFO_VISITOR_DATA_FIELD) {
                // Only whether it is set is logged.
                hasVisitorData = true;
                input.skipField(tag);
            } else if (name != null && wireType == WireFormat.WIRETYPE_VARINT) {
                builder.append(", ").append(name).append(": ").append(input.readInt64());
            } else if (name != null && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                builder.append(", ").append(name).append(": ").append(input.readString());
            } else {
                input.skipField(tag);
            }
        }
        builder.append(", visitorData: ").append(hasVisitorData ? "set" : "not set");
    }

    @Nullable
    private static String getControlPartName(long partType) {
        return switch ((int) partType) {
            case PART_SABR_ERROR -> "error";
            case PART_RELOAD_PLAYER_RESPONSE -> "reload player response";
            case PART_PLAYBACK_START_POLICY -> "playback start policy";
            case PART_SNACKBAR_MESSAGE -> "snackbar message";
            default -> null;
        };
    }

    /**
     * Logs the part as base64, which can be read with 'protoc --decode_raw'.
     * Most responses have the same playback start policy, so a part is logged only if it changed.
     */
    private static synchronized void logControlPart(long partType, byte[] part) {
        String encoded = Base64.encodeToString(part, Base64.NO_WRAP);
        if (!encoded.equals(lastLoggedControlParts.put(partType, encoded))) {
            Logger.printDebug(() -> "SABR " + getControlPartName(partType) + ": " + encoded);
        }
    }
}
