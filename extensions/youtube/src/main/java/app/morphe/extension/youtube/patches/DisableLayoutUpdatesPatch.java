package app.morphe.extension.youtube.patches;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableLayoutUpdatesPatch {

    private static final List<String> REQUEST_HEADER_KEYS = List.of(
            "X-Youtube-Cold-Config-Data",
            "X-Youtube-Cold-Hash-Data",
            "X-Youtube-Hot-Config-Data",
            "X-Youtube-Hot-Hash-Data"
    );

    private static final boolean DISABLE_LAYOUT_UPDATES = Settings.DISABLE_LAYOUT_UPDATES.get();

    /**
     * @param key   Keys to be added to the header of CronetBuilder.
     * @param value Values to be added to the header of CronetBuilder.
     * @return Empty value if setting is enabled.
     */
    public static String disableLayoutUpdates(String key, String value) {
        if (DISABLE_LAYOUT_UPDATES && REQUEST_HEADER_KEYS.contains(key)) {
            Logger.printDebug(() -> "Blocking: " + key);
            return "";
        }

        return value;
    }
}
