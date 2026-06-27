package app.morphe.extension.music.patches.listenbrainz;

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
