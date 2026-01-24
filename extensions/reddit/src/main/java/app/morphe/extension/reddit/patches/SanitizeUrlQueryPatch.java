package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class SanitizeUrlQueryPatch {

    public static boolean stripQueryParameters() {
        return Settings.SANITIZE_URL_QUERY.get();
    }

}
