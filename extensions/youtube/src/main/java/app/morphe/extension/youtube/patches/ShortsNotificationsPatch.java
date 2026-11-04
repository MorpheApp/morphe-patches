/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.lang.reflect.Method;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

public final class ShortsNotificationsPatch {
    private static final int PUSH_NOTIFICATION_RENDERER = 77819057;
    private static final int REEL_WATCH_ENDPOINT_FIELD = 139608561;
    private static final String[] REEL_WATCH_ENDPOINT_EXTENSIONS = {
            "defpackage.blie", // YouTube 21.42+
            "defpackage.bjts"  // YouTube 21.30
    };

    private ShortsNotificationsPatch() {
    }

    public static boolean shouldSuppressShortsNotification(Object pushNotification) {
        return Settings.DISABLE_SHORTS_NOTIFICATIONS.get() && isShortsNotification(pushNotification);
    }

    private static boolean containsReelWatchEndpoint(byte[] serialized) {
        return serialized != null && containsReelWatchEndpoint(ProtoNode.parse(serialized));
    }

    private static boolean containsReelWatchEndpoint(List<ProtoNode> fields) {
        if (fields == null) return false;
        for (ProtoNode field : fields) {
            if (field.getFieldNumber() == REEL_WATCH_ENDPOINT_FIELD && field.children != null) return true;
            if (containsReelWatchEndpoint(field.children)) return true;
        }
        return false;
    }

    private static byte[] serializedPayload(Object value) {
        if (value == null) return null;
        try {
            Method method = value.getClass().getMethod("toByteArray");
            return method.getReturnType() == byte[].class ? (byte[]) method.invoke(value) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean isShortsNotification(Object pushNotification) {
        if (pushNotification == null) return false;

        try {
            Class<?> pushClass = pushNotification.getClass();
            if (pushClass.getField("b").getInt(pushNotification) != PUSH_NOTIFICATION_RENDERER) return false;
            if (containsReelWatchEndpoint(serializedPayload(pushNotification))) return true;

            Object renderer = pushClass.getField("c").get(pushNotification);
            if (renderer == null) return false;
            Object navigationEndpoint = renderer.getClass().getField("f").get(renderer);
            if (navigationEndpoint == null) return false;

            Object extensionStore = navigationEndpoint.getClass().getField("l").get(navigationEndpoint);
            for (String extensionClassName : REEL_WATCH_ENDPOINT_EXTENSIONS) {
                try {
                    Object reelWatchEndpoint = Class.forName(extensionClassName).getField("b").get(null);
                    Object descriptor = reelWatchEndpoint.getClass().getField("d").get(reelWatchEndpoint);

                    for (Method method : extensionStore.getClass().getMethods()) {
                        Class<?>[] parameters = method.getParameterTypes();
                        if (method.getName().equals("n") && parameters.length == 1
                                && parameters[0].isInstance(descriptor)
                                && Boolean.TRUE.equals(method.invoke(extensionStore, descriptor))) return true;
                    }
                } catch (ClassNotFoundException | NoSuchFieldException ignored) {
                    // The generated extension may belong to another supported YouTube schema.
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            Logger.printException(() -> "Shorts push notification detection failed open", ex);
            return false;
        }
    }
}
