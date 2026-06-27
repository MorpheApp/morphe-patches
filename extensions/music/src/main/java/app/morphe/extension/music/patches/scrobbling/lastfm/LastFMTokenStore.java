/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling.lastfm;

import app.morphe.extension.music.settings.Settings;

public class LastFMTokenStore {
    public static boolean store(String sessionKey, String username) {
        Settings.LASTFM_SESSION_KEY.save(sessionKey);
        Settings.LASTFM_USERNAME.save(username);
        return true;
    }

    public static String retrieveSessionKey() {
        return Settings.LASTFM_SESSION_KEY.get();
    }

    public static String retrieveUsername() {
        return Settings.LASTFM_USERNAME.get();
    }

    public static boolean clear() {
        Settings.LASTFM_SESSION_KEY.save("");
        Settings.LASTFM_USERNAME.save("");
        return true;
    }
}
