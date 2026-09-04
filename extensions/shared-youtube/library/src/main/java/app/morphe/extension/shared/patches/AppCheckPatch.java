/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

public class AppCheckPatch {
    @SuppressWarnings("SameParameterValue")
    private static boolean classExists(String className) {
        boolean classExists = false;
        try {
            Class.forName(className);
            classExists = true;
        } catch (ClassNotFoundException ignored) {
        }
        return classExists;
    }

    private static final String MAIN_ACTIVITY_CLASS_YOUTUBE =
            "com.google.android.apps.youtube.app.watchwhile.MainActivity";

    private static final String MAIN_ACTIVITY_CLASS_YOUTUBE_MUSIC =
            "com.google.android.apps.youtube.music.activities.MusicActivity";

    public static final boolean IS_YOUTUBE = classExists(MAIN_ACTIVITY_CLASS_YOUTUBE);

    public static final boolean IS_YOUTUBE_MUSIC = classExists(MAIN_ACTIVITY_CLASS_YOUTUBE_MUSIC);
}