package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HideAutoplayPreviewPatch {
    /**
     * Injection point.
     */
    public static boolean hideAutoplayPreview() {
        return Settings.HIDE_AUTOPLAY_PREVIEW.get();
    }
}
