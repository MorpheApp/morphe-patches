/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * JSON array file in the no backup files directory, so it is never sent anywhere.
 */
final class GhostJsonFile {

    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final String fileName;

    GhostJsonFile(String fileName) {
        this.fileName = fileName;
    }

    private File getFile() {
        return new File(Utils.getContext().getNoBackupFilesDir(), fileName);
    }

    /**
     * @return The saved array, or an empty array if there is none.
     */
    JSONArray read() {
        File file = getFile();
        if (!file.isFile()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException ex) {
            Logger.printException(() -> "Could not read " + fileName, ex);
            return new JSONArray();
        }
    }

    /**
     * Saves the array in the background, or deletes the file if the array is empty.
     */
    void writeAsync(JSONArray array) {
        byte[] data = array.toString().getBytes(StandardCharsets.UTF_8);
        ioExecutor.execute(() -> {
            AtomicFile file = new AtomicFile(getFile());
            if (array.length() == 0) {
                file.delete();
                return;
            }
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(data);
                file.finishWrite(stream);
            } catch (IOException ex) {
                if (stream != null) {
                    file.failWrite(stream);
                }
                Logger.printException(() -> "Could not save " + fileName, ex);
            }
        });
    }
}
