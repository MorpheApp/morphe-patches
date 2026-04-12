package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Utils;

public class VersionCheckPatch {
    private static boolean isVersionOrGreater(String version) {
        return Utils.getAppVersionName().compareTo(version) >= 0;
    }

    public static final boolean IS_20_22_OR_GREATER = isVersionOrGreater("20.22.00");

    public static final boolean IS_20_31_OR_GREATER = isVersionOrGreater("20.31.00");

    public static final boolean IS_20_37_OR_GREATER = isVersionOrGreater("20.37.00");

    public static final boolean IS_20_39_OR_GREATER = isVersionOrGreater("20.39.00");

    public static final boolean IS_20_45_OR_GREATER = isVersionOrGreater("20.45.00");

    public static final boolean IS_21_10_OR_GREATER = isVersionOrGreater("21.10.00");

    public static final boolean IS_21_15_OR_GREATER = isVersionOrGreater("21.15.00");

}
