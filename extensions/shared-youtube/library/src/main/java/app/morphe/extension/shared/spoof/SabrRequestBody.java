/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4316
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
import java.util.Locale;

/**
 * The body of a SABR media request (VideoPlaybackAbrRequest), which the app makes for itself
 * even when it plays the streams of another client.
 */
public final class SabrRequestBody {
    // VideoPlaybackAbrRequest.videoPlaybackUstreamerConfig and VideoPlaybackAbrRequest.streamerContext.
    static final int REQUEST_USTREAMER_CONFIG_FIELD = 5;
    static final int REQUEST_STREAMER_CONTEXT_FIELD = 19;
    // StreamerContext.clientInfo and StreamerContext.poToken.
    static final int STREAMER_CONTEXT_CLIENT_INFO_FIELD = 1;
    static final int STREAMER_CONTEXT_PO_TOKEN_FIELD = 2;
    // Fields of ClientInfo, the same message as the client of InnerTube requests.
    static final int CLIENT_INFO_DEVICE_MAKE_FIELD = 12;
    static final int CLIENT_INFO_DEVICE_MODEL_FIELD = 13;
    static final int CLIENT_INFO_VISITOR_DATA_FIELD = 14;
    static final int CLIENT_INFO_CLIENT_NAME_FIELD = 16;
    static final int CLIENT_INFO_CLIENT_VERSION_FIELD = 17;
    static final int CLIENT_INFO_OS_NAME_FIELD = 18;
    static final int CLIENT_INFO_OS_VERSION_FIELD = 19;
    static final int CLIENT_INFO_HL_FIELD = 21;
    static final int CLIENT_INFO_GL_FIELD = 22;

    private SabrRequestBody() {
    }

    /**
     * The client of the media requests, with only what the stream url already tells the server.
     * The app would otherwise name itself and the device it runs on.
     */
    @SuppressWarnings("SameParameterValue")
    static byte[] buildClientInfo(ClientType client, Locale locale) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeInt32(CLIENT_INFO_CLIENT_NAME_FIELD, client.id);
        output.writeString(CLIENT_INFO_CLIENT_VERSION_FIELD, client.clientVersion);
        String language = locale.getLanguage();
        if (!language.isEmpty()) {
            output.writeString(CLIENT_INFO_HL_FIELD, language);
        }
        String country = locale.getCountry();
        if (!country.isEmpty()) {
            output.writeString(CLIENT_INFO_GL_FIELD, country);
        }
        output.flush();
        return bytes.toByteArray();
    }

    /**
     * Replaces the client of the request, and removes the PoToken of the app.
     * The other fields are kept as they are.
     */
    static byte[] replaceClientInfo(byte[] body, byte[] clientInfo) throws IOException {
        return replaceClientInfo(body, clientInfo, null, null, null);
    }

    /**
     * Replaces the client of the request, and the PoToken of the app with the PoToken of the client.
     * The other fields are kept as they are.
     *
     * @param poToken         PoToken of the client, or null if the client has none.
     * @param sabrContexts    SABR contexts the request must send back, or null if the client has none.
     * @param ustreamerConfig Streaming config of the session of the client, or null to keep the one of the app.
     */
    static byte[] replaceClientInfo(byte[] body, byte[] clientInfo, @Nullable byte[] poToken,
                                    @Nullable SabrContexts.ToSend sabrContexts,
                                    @Nullable byte[] ustreamerConfig) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length + clientInfo.length);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        CodedInputStream input = CodedInputStream.newInstance(body);
        while (true) {
            final int start = input.getTotalBytesRead();
            final int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            if (isMessageField(tag, REQUEST_STREAMER_CONTEXT_FIELD)) {
                output.writeByteArray(REQUEST_STREAMER_CONTEXT_FIELD,
                        replaceStreamerContextClient(input.readByteArray(), clientInfo, poToken, sabrContexts));
            } else if (ustreamerConfig != null && isMessageField(tag, REQUEST_USTREAMER_CONFIG_FIELD)) {
                input.skipField(tag);
                output.writeByteArray(REQUEST_USTREAMER_CONFIG_FIELD, ustreamerConfig);
            } else {
                copyField(body, start, input, tag, output);
            }
        }
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] replaceStreamerContextClient(byte[] streamerContext, byte[] clientInfo,
                                                       @Nullable byte[] poToken,
                                                       @Nullable SabrContexts.ToSend sabrContexts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(streamerContext.length + clientInfo.length);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        CodedInputStream input = CodedInputStream.newInstance(streamerContext);
        boolean clientInfoWritten = false;
        while (true) {
            final int start = input.getTotalBytesRead();
            final int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            final int field = WireFormat.getTagFieldNumber(tag);
            if (field == STREAMER_CONTEXT_CLIENT_INFO_FIELD) {
                input.skipField(tag);
                if (!clientInfoWritten) {
                    output.writeByteArray(STREAMER_CONTEXT_CLIENT_INFO_FIELD, clientInfo);
                    clientInfoWritten = true;
                }
            } else if (field == STREAMER_CONTEXT_PO_TOKEN_FIELD
                    || (sabrContexts != null && (field == SabrContexts.STREAMER_CONTEXT_SABR_CONTEXTS_FIELD
                    || field == SabrContexts.STREAMER_CONTEXT_UNSENT_SABR_CONTEXTS_FIELD))) {
                input.skipField(tag);
            } else {
                copyField(streamerContext, start, input, tag, output);
            }
        }
        if (!clientInfoWritten) {
            output.writeByteArray(STREAMER_CONTEXT_CLIENT_INFO_FIELD, clientInfo);
        }
        if (poToken != null) {
            output.writeByteArray(STREAMER_CONTEXT_PO_TOKEN_FIELD, poToken);
        }
        if (sabrContexts != null) {
            for (byte[] sabrContext : sabrContexts.contexts()) {
                output.writeByteArray(SabrContexts.STREAMER_CONTEXT_SABR_CONTEXTS_FIELD, sabrContext);
            }
            for (int type : sabrContexts.unsentTypes()) {
                output.writeInt32(SabrContexts.STREAMER_CONTEXT_UNSENT_SABR_CONTEXTS_FIELD, type);
            }
        }
        output.flush();
        return bytes.toByteArray();
    }

    /**
     * Copies the field whose tag was just read, as it is.
     *
     * @param start Where the tag of the field begins in the message.
     */
    private static void copyField(byte[] message, int start, CodedInputStream input, int tag,
                                  CodedOutputStream output) throws IOException {
        input.skipField(tag);
        output.writeRawBytes(message, start, input.getTotalBytesRead() - start);
    }

    private static boolean isMessageField(int tag, int field) {
        return WireFormat.getTagFieldNumber(tag) == field
                && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }

    @Nullable
    static byte[] readMessageField(byte[] message, int field) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        for (int tag; (tag = input.readTag()) != 0; ) {
            if (isMessageField(tag, field)) {
                return input.readByteArray();
            }
            input.skipField(tag);
        }
        return null;
    }
}
