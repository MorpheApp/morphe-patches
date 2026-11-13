/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4755
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.WireFormat;

import java.io.IOException;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    /**
     * Share entity endpoint nested in the command of the share entity service endpoint.
     * Used when the serialized share entity of the share entity service endpoint itself is empty.
     */
    private static final int NESTED_SHARE_ENTITY_ENDPOINT_FIELD = 387863284;
    private static final int NESTED_SERIALIZED_SHARE_ENTITY_FIELD = 1;

    /**
     * Serialized share entity of the share entity service endpoint.
     */
    private static final int SERIALIZED_SHARE_ENTITY_FIELD = 1;

    // Share entity fields the app builds a link from, the same fields as the share entity of YouTube.
    private static final int ENTITY_VIDEO_ID_FIELD = 1;
    private static final int ENTITY_PLAYLIST_ID_FIELD = 2;
    private static final int ENTITY_CHANNEL_ID_FIELD = 3;

    /**
     * Nested messages are at most a few levels deep.
     */
    private static final int MAX_SEARCH_DEPTH = 10;

    /**
     * Injection point.
     * Without a network connection, the app does not open the in-app share sheet. It builds the
     * music.youtube.com link of the song, playlist or artist itself and opens the system share sheet.
     *
     * @param networkConnected If the network is connected.
     * @param endpoint         Share entity service endpoint.
     * @return If the network is reported as connected, which opens the in-app share sheet.
     */
    public static boolean useInAppShareSheet(boolean networkConnected, Object endpoint) {
        if (!networkConnected || !Settings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return networkConnected;
        }

        try {
            String serializedShareEntity = findSerializedShareEntity(endpoint);
            if (hasShareLink(serializedShareEntity)) {
                return false;
            }
            Logger.printDebug(() -> "No link for share entity, opening the in-app share sheet: "
                    + serializedShareEntity);
        } catch (Exception ex) {
            Logger.printException(() -> "useInAppShareSheet failure", ex);
        }

        return true;
    }

    /**
     * Injection point.
     *
     * @param serializedShareEntity Serialized share entity of the share entity service endpoint.
     * @param endpoint              Share entity service endpoint.
     * @return The serialized share entity the app builds the link from.
     */
    public static String getSerializedShareEntity(String serializedShareEntity, Object endpoint) {
        if (!TextUtils.isEmpty(serializedShareEntity) || !Settings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return serializedShareEntity;
        }

        try {
            String nestedShareEntity = findSerializedShareEntity(endpoint);
            if (nestedShareEntity != null) {
                return nestedShareEntity;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSerializedShareEntity failure", ex);
        }

        return serializedShareEntity;
    }

    /**
     * @return The serialized share entity of the endpoint, or of the share entity endpoint nested in its command.
     */
    @Nullable
    private static String findSerializedShareEntity(Object endpoint) throws IOException {
        byte[] endpointBytes = ((MessageLite) endpoint).toByteArray();

        String serializedShareEntity = new ProtoMessage(endpointBytes).stringField(SERIALIZED_SHARE_ENTITY_FIELD);
        if (!serializedShareEntity.isEmpty()) {
            return serializedShareEntity;
        }

        byte[] nestedEndpoint = findField(endpointBytes, NESTED_SHARE_ENTITY_ENDPOINT_FIELD, 0);
        if (nestedEndpoint == null) {
            return null;
        }

        String nestedShareEntity = new ProtoMessage(nestedEndpoint).stringField(NESTED_SERIALIZED_SHARE_ENTITY_FIELD);
        return nestedShareEntity.isEmpty() ? null : nestedShareEntity;
    }

    /**
     * Searches the nested messages for a length delimited field.
     *
     * @return The value of the first matching field, or null if not found.
     */
    @Nullable
    private static byte[] findField(byte[] message, int fieldNumber, int depth) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        for (int tag; (tag = input.readTag()) != 0;) {
            if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                input.skipField(tag);
                continue;
            }

            byte[] value = input.readByteArray();
            if (WireFormat.getTagFieldNumber(tag) == fieldNumber) {
                return value;
            }

            if (depth < MAX_SEARCH_DEPTH) {
                try {
                    byte[] nestedValue = findField(value, fieldNumber, depth + 1);
                    if (nestedValue != null) {
                        return nestedValue;
                    }
                } catch (IOException ex) {
                    // Not a message, such as a string.
                    Logger.printDebug(() -> "Ignoring field: " + new String(message));
                }
            }
        }

        return null;
    }

    /**
     * @return If the share entity has a video, playlist or channel id, which the app builds a link from.
     */
    private static boolean hasShareLink(@Nullable String serializedShareEntity) {
        if (TextUtils.isEmpty(serializedShareEntity)) {
            return false;
        }

        final byte[] entityBytes;
        try {
            String base64 = Uri.decode(serializedShareEntity)
                    .replace('-', '+')
                    .replace('_', '/');
            entityBytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Share entity is not Base64: " + serializedShareEntity);
            return false;
        }

        ProtoMessage entity = new ProtoMessage(entityBytes);
        return !entity.messageField(ENTITY_VIDEO_ID_FIELD).isEmpty()
                || !entity.messageField(ENTITY_PLAYLIST_ID_FIELD).isEmpty()
                || !entity.messageField(ENTITY_CHANNEL_ID_FIELD).isEmpty();
    }
}
