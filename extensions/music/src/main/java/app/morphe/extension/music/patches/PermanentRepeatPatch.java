package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class PermanentRepeatPatch {

    /**
     * Injection point
     */
    public static boolean permanentRepeat() {
        return Settings.PERMANENT_REPEAT.get();
    }
}
