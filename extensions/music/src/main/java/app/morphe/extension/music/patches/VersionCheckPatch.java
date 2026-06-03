package app.morphe.extension.music.patches;

import app.morphe.extension.shared.Utils;

public class VersionCheckPatch {
    private static boolean isVersionOrGreater(String version) {
        return Utils.getAppVersionName().compareTo(version) >= 0;
    }

    public static final boolean IS_8_40_OR_GREATER = isVersionOrGreater("8.40.00");
    public static final boolean IS_9_00_OR_GREATER = isVersionOrGreater("9.00.00");
}