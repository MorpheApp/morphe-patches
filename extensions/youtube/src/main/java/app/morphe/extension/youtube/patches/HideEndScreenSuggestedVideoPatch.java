package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HideEndScreenSuggestedVideoPatch {
    /**
     * Injection point.
     */
    public static boolean hideEndScreenSuggestedVideo() {
        return Settings.HIDE_END_SCREEN_SUGGESTED_VIDEO.get();
    }
}
