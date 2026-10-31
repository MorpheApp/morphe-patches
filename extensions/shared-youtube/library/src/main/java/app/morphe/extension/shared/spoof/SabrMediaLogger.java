/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Debug logging of SABR media requests and responses while spoofing.
 * <p>
 * The app makes the media requests itself, even when it plays the streams of another client.
 * This logs which client the requests name, and the parts of the responses that are not media.
 */
public final class SabrMediaLogger {
    // VideoPlaybackAbrRequest.streamerContext.
    private static final int REQUEST_STREAMER_CONTEXT_FIELD = 19;
    // StreamerContext.clientInfo and StreamerContext.poToken.
    private static final int STREAMER_CONTEXT_CLIENT_INFO_FIELD = 1;
    private static final int STREAMER_CONTEXT_PO_TOKEN_FIELD = 2;
    // ClientInfo.visitorData. Only whether it is set is logged.
    private static final int CLIENT_INFO_VISITOR_DATA_FIELD = 14;

    // UMP parts of media responses that are logged.
    private static final int PART_SABR_ERROR = 44;
    private static final int PART_RELOAD_PLAYER_RESPONSE = 46;
    private static final int PART_PLAYBACK_START_POLICY = 47;
    private static final int PART_SNACKBAR_MESSAGE = 67;

    private static final int STREAM_PROTECTION_STATUS_OK = 1;

    /**
     * Media requests repeat every few seconds, so a request is logged only if it differs from the last one.
     */
    @Nullable
    private static String lastLoggedRequest;

    private static final Map<Long, String> lastLoggedControlParts = new HashMap<>();

    private SabrMediaLogger() {
    }

    public static boolean isEnabled() {
        return BaseSettings.DEBUG.get()
                && SpoofVideoStreamsPatch.isPatchIncluded()
                && SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get();
    }

    /**
     * @param url  Url of a SABR media request, which names the client the streams are from.
     * @param body The request body the app made.
     */
    public static synchronized void logRequest(String url, @Nullable byte[] body) {
        try {
            StringBuilder builder = new StringBuilder("url client: ")
                    .append(Uri.parse(url).getQueryParameter("c"));

            byte[] streamerContext = body == null
                    ? null
                    : readMessageField(body, REQUEST_STREAMER_CONTEXT_FIELD);
            if (streamerContext == null) {
                builder.append(", no streamer context");
            } else {
                byte[] clientInfo = readMessageField(streamerContext, STREAMER_CONTEXT_CLIENT_INFO_FIELD);
                if (clientInfo == null) {
                    builder.append(", no client info");
                } else {
                    appendClientInfo(builder, clientInfo);
                }
                byte[] poToken = readMessageField(streamerContext, STREAMER_CONTEXT_PO_TOKEN_FIELD);
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
            case 12 -> "deviceMake";
            case 13 -> "deviceModel";
            case 16 -> "clientName";
            case 17 -> "clientVersion";
            case 18 -> "osName";
            case 19 -> "osVersion";
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
    private static byte[] readMessageField(byte[] message, int field) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        for (int tag; (tag = input.readTag()) != 0; ) {
            if (WireFormat.getTagFieldNumber(tag) == field
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return input.readByteArray();
            }
            input.skipField(tag);
        }
        return null;
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
     * @return If the part of a media response is logged by {@link #logControlPart(long, byte[])}.
     */
    public static boolean isLoggedPart(long partType) {
        return getControlPartName(partType) != null;
    }

    /**
     * Logs the part as base64, which can be read with 'protoc --decode_raw'.
     * Most responses have the same playback start policy, so a part is logged only if it changed.
     */
    public static synchronized void logControlPart(long partType, byte[] part) {
        String encoded = Base64.encodeToString(part, Base64.NO_WRAP);
        if (!encoded.equals(lastLoggedControlParts.put(partType, encoded))) {
            Logger.printDebug(() -> "SABR " + getControlPartName(partType) + ": " + encoded);
        }
    }

    public static void logProtectionStatus(int status) {
        if (status != STREAM_PROTECTION_STATUS_OK) {
            Logger.printDebug(() -> "SABR stream protection status: " + status);
        }
    }
}
