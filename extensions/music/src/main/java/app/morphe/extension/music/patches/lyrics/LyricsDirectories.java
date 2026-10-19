/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Where the lyrics feature keeps what it has fetched, under the app's own files rather than the
 * cache directory: a dictionary costs tens of megabytes and minutes to build, and lyrics that
 * vanish leave the song without them when there is no network to fetch them back. The two sit in
 * separate directories because they are not spent together.
 */
public final class LyricsDirectories {

    public static final String ROMAN_DICT = "romandict";
    public static final String OFFLINE = "offline";

    private static final String ROOT_NAME = "morphe_lyrics";

    private LyricsDirectories() {
    }

    @Nullable
    public static File directory(String name) {
        try {
            Context context = Utils.getContext();
            if (context == null) return null;
            File directory = new File(new File(context.getFilesDir(), ROOT_NAME), name);
            if (!directory.exists() && !directory.mkdirs()) return null;
            return directory;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not reach the lyrics directory " + name, ex);
            return null;
        }
    }
}
