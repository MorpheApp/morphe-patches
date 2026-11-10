/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Applies a settings export chosen at patch time, once on the first launch after install,
 * and again only when a build carries a different profile. Changes made later in the app are kept.
 */
@SuppressWarnings("unused")
public final class SettingsProfilePatch {

    /**
     * Hex digits kept from the SHA-256 of the profile.
     */
    private static final int HASH_LENGTH = 16;

    /**
     * Settings that belong to one device. A profile import keeps the device's own values.
     */
    private static final Setting<?>[] DEVICE_SETTINGS = {
            Settings.SB_PRIVATE_USER_ID,
            Settings.SB_LOCAL_TIME_SAVED_NUMBER_SEGMENTS,
            Settings.SB_LOCAL_TIME_SAVED_MILLISECONDS
    };

    /**
     * The profile given as the patch option, base64 encoded. Replaced during patching.
     */
    private static String profile() {
        return "";
    }

    /**
     * Injection point.
     */
    public static void onCreate(Activity activity) {
        try {
            String encoded = profile();
            if (encoded.isEmpty()) {
                return;
            }

            String hash = hash(encoded);
            if (hash.equals(Settings.SETTINGS_PROFILE_APPLIED.get())) {
                return;
            }

            String text = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            final boolean restart = apply(activity, parse(text));
            Settings.SETTINGS_PROFILE_APPLIED.save(hash);
            Logger.printInfo(() -> "Settings profile " + hash + " applied");
            if (restart) {
                Utils.restartApp(activity);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCreate failure", ex);
        }
    }

    /**
     * Reads settings as Morphe exports them (no outer braces, trailing comma) or as plain JSON.
     */
    private static JSONObject parse(String text) throws JSONException {
        String json = text.trim();
        if (json.endsWith(",")) {
            json = json.substring(0, json.length() - 1);
        }
        if (!json.startsWith("{")) {
            json = "{" + json + "}";
        }
        return new JSONObject(json);
    }

    private static String hash(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, HASH_LENGTH);
    }

    /**
     * Makes the settings match the profile. Settings not in the profile go back to default,
     * except the device settings, which keep their current value.
     *
     * @return If a changed setting needs an app restart.
     */
    private static boolean apply(Activity activity, JSONObject profile) throws JSONException {
        JSONObject current = parse(Setting.exportToJson(null));
        for (Setting<?> setting : DEVICE_SETTINGS) {
            String key = exportKey(setting);
            profile.remove(key);
            if (current.has(key)) {
                profile.put(key, current.get(key));
            }
        }

        // An open settings screen otherwise writes its old values back over the import.
        final boolean wasImporting = AbstractPreferenceFragment.settingImportInProgress;
        AbstractPreferenceFragment.settingImportInProgress = true;
        try {
            return Setting.importFromJSON(activity, profile.toString());
        } finally {
            AbstractPreferenceFragment.settingImportInProgress = wasImporting;
        }
    }

    /**
     * @return The key a setting has in an export: its key without the "morphe_" prefix.
     */
    private static String exportKey(Setting<?> setting) {
        final String prefix = "morphe_";
        return setting.key.startsWith(prefix) ? setting.key.substring(prefix.length()) : setting.key;
    }
}
