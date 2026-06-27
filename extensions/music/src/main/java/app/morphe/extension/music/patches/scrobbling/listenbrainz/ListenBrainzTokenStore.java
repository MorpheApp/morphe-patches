/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling.listenbrainz;

import app.morphe.extension.music.settings.Settings;

public class ListenBrainzTokenStore {
    public static boolean store(String token) {
        Settings.LISTENBRAINZ_USER_TOKEN.save(token);
        return true;
    }

    public static String retrieve() {
        return Settings.LISTENBRAINZ_USER_TOKEN.get();
    }

    public static boolean clear() {
        Settings.LISTENBRAINZ_USER_TOKEN.save("");
        return true;
    }
}
