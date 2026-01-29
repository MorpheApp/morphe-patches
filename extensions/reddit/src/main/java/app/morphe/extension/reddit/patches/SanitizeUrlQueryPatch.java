package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class SanitizeUrlQueryPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static boolean stripQueryParameters() {
        return Settings.SANITIZE_URL_QUERY.get();
    }
}
