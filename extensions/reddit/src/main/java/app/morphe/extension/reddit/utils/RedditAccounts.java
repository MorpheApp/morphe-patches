/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Reddit stores the active account name in a Jetpack DataStore, which is migrated from shared preferences
 * of the same name the first time it's read.
 */
public final class RedditAccounts {

    private static final String STORE_NAME = "com.reddit.auth_active";
    private static final String ACCOUNT_TYPE = "com.reddit.account";
    private static final String ACCOUNT_NAME_KEY = "active_session_name";

    /**
     * Account names Reddit uses when not logged in.
     */
    private static final String LOGGED_OUT_ACCOUNT_NAME = "logged_out_session_pref";
    private static final String INCOGNITO_ACCOUNT_NAME = "incognito_session_pref";

    // Field numbers of androidx.datastore.preferences PreferenceMap.
    private static final int ENTRY_KEY_FIELD = 1;
    private static final int ENTRY_VALUE_FIELD = 2;
    /**
     * The string field number with the length delimited wire type.
     */
    private static final byte VALUE_STRING_TAG = (5 << 3) | 2;

    private RedditAccounts() {
    }

    /**
     * Reads the store on every call, because the account can change while the app is running.
     *
     * @return The account name, or null if logged out or it's unknown.
     */
    @Nullable
    public static String getActive() {
        try {
            Context context = Utils.getContext();

            String name = null;
            File file = new File(context.getFilesDir(), "datastore/" + STORE_NAME + ".preferences_pb");
            if (file.exists()) {
                name = readStringPreference(Files.readAllBytes(file.toPath()), ACCOUNT_NAME_KEY);
            }
            if (name == null) {
                SharedPreferences preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
                name = preferences.getString(ACCOUNT_NAME_KEY, null);
            }

            if (name == null) return null;

            // The name is prefixed with the store name, such as "com.reddit.auth_active.<username>".
            if (name.startsWith(STORE_NAME + ".")) {
                name = name.substring(STORE_NAME.length() + 1);
            }

            return name.isEmpty() || name.equals(LOGGED_OUT_ACCOUNT_NAME) || name.equals(INCOGNITO_ACCOUNT_NAME)
                    ? null
                    : name;
        } catch (Exception ex) {
            Logger.printException(() -> "getActive failure", ex);
            return null;
        }
    }

    /**
     * @return The names of the accounts logged in on this device.
     */
    public static List<String> getLoggedIn() {
        List<String> names = new ArrayList<>();
        try {
            for (Account account : AccountManager.get(Utils.getContext()).getAccountsByType(ACCOUNT_TYPE)) {
                // Reddit also has accounts for logged out and incognito sessions,
                // such as "Reddit for Android", and usernames can't contain spaces.
                if (!account.name.contains(" ")) {
                    names.add(account.name);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getLoggedIn failure", ex);
        }
        return names;
    }

    /**
     * PreferenceMap has repeated entries of a key and a value, and the value has one field per type.
     *
     * @return The string value of a key in a serialized PreferenceMap, or null if it's not found.
     */
    @Nullable
    private static String readStringPreference(byte[] data, String key) {
        int[] position = {0};
        while (position[0] < data.length) {
            readVarint(data, position); // Entry field.
            final int entryEnd = readVarint(data, position) + position[0];

            String entryKey = null;
            String entryValue = null;
            while (position[0] < entryEnd) {
                final int field = readVarint(data, position) >>> 3;
                final int length = readVarint(data, position);
                final int start = position[0];
                if (field == ENTRY_KEY_FIELD) {
                    entryKey = new String(data, start, length, StandardCharsets.UTF_8);
                } else if (field == ENTRY_VALUE_FIELD && data[start] == VALUE_STRING_TAG) {
                    position[0]++;
                    final int stringLength = readVarint(data, position);
                    entryValue = new String(data, position[0], stringLength, StandardCharsets.UTF_8);
                }
                position[0] = start + length;
            }

            if (key.equals(entryKey)) return entryValue;
        }
        return null;
    }

    private static int readVarint(byte[] data, int[] position) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            final byte b = data[position[0]++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalStateException("Malformed varint");
    }
}
