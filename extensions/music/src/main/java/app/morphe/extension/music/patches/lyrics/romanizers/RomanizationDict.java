/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

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
import java.nio.file.Files;

import app.morphe.extension.music.patches.lyrics.LyricsDirectories;
import app.morphe.extension.shared.Logger;

/**
 * What the two reading dictionaries have in common: how they are fetched and how the settings row
 * hears about it. Where they are kept is {@link LyricsDirectories}' business.
 */
public final class RomanizationDict {

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

    static final class Channel implements Listener {

        private volatile Listener to;
        private volatile String fileName;
        private volatile int percent;
        private volatile boolean building;

        /** Takes over as the listener, and is told where the build is if one is going. */
        void attach(Listener listener, boolean running) {
            to = listener;
            if (!running) return;
            if (building) {
                listener.onBuilding(percent);
            } else if (fileName != null) {
                listener.onDownload(fileName, percent);
            }
        }

        /** Lets go, because holding the listener past the build would keep its page alive. */
        void detach() {
            to = null;
            fileName = null;
            building = false;
        }

        @Override
        public void onDownload(String fileName, int percent) {
            this.fileName = fileName;
            this.percent = percent;
            this.building = false;
            Listener current = to;
            if (current != null) current.onDownload(fileName, percent);
        }

        @Override
        public void onBuilding(int percent) {
            this.percent = percent;
            this.building = true;
            Listener current = to;
            if (current != null) current.onBuilding(percent);
        }

        @Override
        public void onReady(int entries) {
            Listener current = to;
            if (current != null) current.onReady(entries);
        }

        @Override
        public void onFailed() {
            Listener current = to;
            if (current != null) current.onFailed();
        }
    }

    @Nullable
    static File directory() {
        return LyricsDirectories.directory(LyricsDirectories.ROMAN_DICT);
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

            // A compressed response reports no length, so the file is named before the wait starts.
            if (expected <= 0) listener.onDownload(reportedName, 0);

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
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the notice " + fileName, ex);
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
