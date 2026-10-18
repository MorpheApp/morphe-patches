/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * What the two reading dictionaries have in common: where they are kept, how they are fetched,
 * and how the settings row hears about it. Nothing is built anywhere else, so a version moving
 * is all there is to a new dictionary, and the system may empty the cache at any time, so every
 * path here treats a missing file as ordinary.
 */
public final class RomanizationDict {

    private static final String DIRECTORY_NAME = "morphe_lyrics_cjkromadict";
    private static final String DOWNLOAD_SUFFIX = ".part";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_BYTES = 1 << 16;

    private static final int PROGRESS_STEP_PERCENT = 2;

    /** How the settings row follows along with a dictionary being fetched and built. */
    public interface Listener {
        void onDownload(String fileName, int percent);
        void onBuilding(int percent);
        void onReady(int entries);
        void onFailed();
    }

    private RomanizationDict() {
    }

    @Nullable
    static File directory() {
        try {
            Context context = Utils.getContext();
            if (context == null) return null;
            File directory = new File(context.getCacheDir(), DIRECTORY_NAME);
            if (!directory.exists() && !directory.mkdirs()) return null;
            return directory;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not reach the romanization dictionary directory", ex);
            return null;
        }
    }

    /** Fetches a file under a name that says it is unfinished, so an interrupted
     * download is never mistaken for a whole file. */
    static void download(String url, File target, String reportedName, Listener listener)
            throws IOException {
        File partial = new File(target.getPath() + DOWNLOAD_SUFFIX);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "MorpheMusic");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("download of " + reportedName + " returned " + status);
            }

            long expected = connection.getContentLength();
            long written = 0;
            int lastPercent = -1;

            try (InputStream in = new BufferedInputStream(connection.getInputStream(),
                    BUFFER_BYTES);
                 OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (expected > 0) {
                        int percent = (int) (written * 100 / expected);
                        if (percent - lastPercent >= PROGRESS_STEP_PERCENT || percent >= 100) {
                            lastPercent = percent;
                            listener.onDownload(reportedName, percent);
                        }
                    }
                }
                out.flush();
            }

            if (expected > 0 && written != expected) {
                throw new IOException("download of " + reportedName + " was cut short");
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("could not replace an earlier " + reportedName);
            }
            if (!partial.renameTo(target)) {
                throw new IOException("could not put " + reportedName + " in place");
            }
            listener.onDownload(reportedName, 100);
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
        }
    }

    static void writeNotice(File directory, String fileName, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(directory, fileName))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    static String readNotice(File directory, String fileName) {
        File file = new File(directory, fileName);
        if (!file.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    static void commit(File built, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("could not replace the earlier table");
        }
        if (!built.renameTo(target)) {
            throw new IOException("could not put the table in place");
        }
    }
}
