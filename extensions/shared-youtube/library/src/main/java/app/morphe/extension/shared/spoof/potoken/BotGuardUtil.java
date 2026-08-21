/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

public final class BotGuardUtil {

    public static String stringToU8(String identifier) {
        return newUint8Array(identifier.getBytes(StandardCharsets.UTF_8));
    }

    public static String u8ToBase64(String poToken) {
        if (Utils.isNotEmpty(poToken)) {
            String[] parts = poToken.split(",");
            byte[] bytes = new byte[parts.length];

            for (int i = 0; i < parts.length; i++) {
                try {
                    int val = Integer.parseInt(parts[i].trim());
                    bytes[i] = (byte) val;
                } catch (NumberFormatException ignored) {
                    // Ignore or handle error
                    bytes[i] = 0;
                }
            }

            return Base64.encodeToString(bytes, Base64.NO_WRAP)
                    .replace("+", "-")
                    .replace("/", "_");
        }

        return null;
    }

    public static String base64ToU8(String base64) {
        return newUint8Array(base64ToByteString(base64));
    }

    private static String newUint8Array(byte[] contents) {
        StringBuilder sb = new StringBuilder();
        sb.append("new Uint8Array([");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(Byte.toUnsignedInt(contents[i]));
        }
        sb.append("])");
        return sb.toString();
    }

    private static byte[] base64ToByteString(String base64) {
        String base64Mod = base64
                .replace('-', '+')
                .replace('_', '/')
                .replace('.', '=');
        try {
            return Base64.decode(base64Mod, Base64.DEFAULT);
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Cannot base64 decode", ex);
        }

        return new byte[0];
    }
}