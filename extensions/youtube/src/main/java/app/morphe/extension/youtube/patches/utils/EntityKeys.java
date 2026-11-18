/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.net.Uri;
import android.util.Base64;

import com.google.protobuf.ByteString;

import app.morphe.extension.shared.innertube.Offline.EntityKey;

public final class EntityKeys {

    private static final int PERSISTENCE = 1;

    /**
     * @return The key of the entity, as the app encodes it.
     */
    public static String key(int entityType, ByteString entityId) {
        EntityKey key = EntityKey.newBuilder()
                .setEntityId(entityId)
                .setEntityType(entityType)
                .setPersistence(PERSISTENCE)
                .build();
        return Uri.encode(Base64.encodeToString(key.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP));
    }
}
