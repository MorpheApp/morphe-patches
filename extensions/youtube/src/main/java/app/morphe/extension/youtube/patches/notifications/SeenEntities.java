/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.youtube.patches.utils.EntityKeys;
import app.morphe.extension.youtube.patches.utils.ProtoBytes;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * The unseen state of the notifications, which the notifications response stores as entities
 * outside of the notifications. The dot of a notification shows the entity of its key.
 */
final class SeenEntities {

    private static final int ENTITY_STORE = 777;
    private static final int MUTATIONS = 1;
    private static final int MUTATION = 1;
    private static final int MUTATION_KEY = 1;
    private static final int MUTATION_TYPE = 2;
    private static final int MUTATION_PAYLOAD = 3;
    private static final int MUTATION_TYPE_REPLACE = 1;
    private static final int UNSEEN_ENTITY = 266;
    private static final int ENTITY_KEY = 1;
    private static final int ENTITY_UNSEEN = 2;

    @Nullable
    private final ProtoNode mutations;
    private final Map<String, Boolean> unseen = new HashMap<>();
    private final ByteArrayOutputStream added = new ByteArrayOutputStream();

    SeenEntities(List<ProtoNode> response) {
        mutations = ProtoNode.field(ProtoNode.message(response, ENTITY_STORE), MUTATIONS);
        if (mutations == null || mutations.children == null) {
            return;
        }
        for (ProtoNode mutation : mutations.children) {
            if (mutation.getFieldNumber() != MUTATION || mutation.children == null) {
                continue;
            }
            List<ProtoNode> entity = ProtoNode.message(mutation.children, MUTATION_PAYLOAD, UNSEEN_ENTITY);
            ProtoNode key = ProtoNode.field(entity, ENTITY_KEY);
            if (key == null || !key.isText()) {
                continue;
            }
            ProtoNode value = ProtoNode.field(entity, ENTITY_UNSEEN);
            Long varint = value == null ? null : value.getVarint();
            unseen.put(key.getText(), varint != null && varint != 0);
        }
    }

    boolean canAdd() {
        return mutations != null && mutations.children != null;
    }

    /**
     * @return The unseen state the response has for the key, or null if it has none.
     */
    @Nullable
    Boolean isUnseen(@Nullable String key) {
        return key == null ? null : unseen.get(key);
    }

    void add(String key, boolean isUnseen) throws IOException {
        if (unseen.containsKey(key)) {
            return;
        }
        unseen.put(key, isUnseen);

        byte[] entity = ProtoBytes.encode(output -> {
            output.writeString(ENTITY_KEY, key);
            output.writeUInt64(ENTITY_UNSEEN, isUnseen ? 1 : 0);
        });
        byte[] payload = ProtoBytes.message(UNSEEN_ENTITY, entity);
        byte[] mutation = ProtoBytes.encode(output -> {
            output.writeString(MUTATION_KEY, key);
            output.writeUInt64(MUTATION_TYPE, MUTATION_TYPE_REPLACE);
            output.writeByteArray(MUTATION_PAYLOAD, payload);
        });
        added.write(ProtoBytes.message(MUTATION, mutation));
    }

    /**
     * Adds the entities of {@link #add} to the response.
     */
    void write() {
        if (mutations != null && added.size() > 0) {
            mutations.setPayload(ProtoBytes.concat(mutations.getPayload(), added.toByteArray()));
        }
    }

    /**
     * @return The key of the unseen entity of the notification, as the app encodes it.
     */
    static String key(long notificationId) {
        return EntityKeys.key(UNSEEN_ENTITY, ByteString.copyFromUtf8(Long.toUnsignedString(notificationId)));
    }
}
