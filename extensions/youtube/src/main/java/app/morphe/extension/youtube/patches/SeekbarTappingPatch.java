package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class SeekbarTappingPatch {
    public static boolean seekbarTappingEnabled() {
        return Settings.SEEKBAR_TAPPING.get();
    }
}
