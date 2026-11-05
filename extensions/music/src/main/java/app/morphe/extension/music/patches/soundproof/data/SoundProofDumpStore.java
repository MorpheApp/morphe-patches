/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.patches.soundproof.domain.ArtistDirectory;
import app.morphe.extension.shared.requests.Requester;

/**
 * Keeps a local copy of the SoundProof database and refreshes it once a day.
 * <p>
 * The database is downloaded at runtime rather than bundled at patch time, so that it stays
 * fresh and is not redistributed inside the patched app. All methods do I/O and must be
 * called off the main thread.
 */
public final class SoundProofDumpStore {

    private static final String DUMP_URL =
            "https://raw.githubusercontent.com/NuclearBlox/SoundProof-Dumps/main/data/artists.json";

    private static final long REFRESH_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final int TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(20);
    private static final String CACHE_FILE_NAME = "artists.json";
    private static final String DOWNLOAD_FILE_NAME = "artists.json.download";

    private final File storageDirectory;
    private final File cacheFile;
    private final File downloadFile;

    /**
     * @param storageDirectory Private directory owned by this feature, created when missing.
     */
    public SoundProofDumpStore(File storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.cacheFile = new File(storageDirectory, CACHE_FILE_NAME);
        this.downloadFile = new File(storageDirectory, DOWNLOAD_FILE_NAME);
    }

    public Optional<ArtistDirectory> loadCached() throws IOException {
        if (!cacheFile.isFile()) {
            return Optional.empty();
        } else {
            try (InputStream cacheStream = new FileInputStream(cacheFile)) {
                return Optional.of(SoundProofDumpParser.parse(cacheStream));
            }
        }
    }

    public boolean isRefreshDue(long nowMillis) {
        return !cacheFile.isFile() || nowMillis - cacheFile.lastModified() >= REFRESH_INTERVAL_MILLIS;
    }

    /**
     * Downloads the database, and replaces the local copy only once the download parses.
     */
    public ArtistDirectory download() throws IOException {
        if (!storageDirectory.isDirectory() && !storageDirectory.mkdirs()) {
            throw new IOException("Cannot create " + storageDirectory);
        }
        downloadTo(downloadFile);
        ArtistDirectory directory;
        try (InputStream downloadStream = new FileInputStream(downloadFile)) {
            directory = SoundProofDumpParser.parse(downloadStream);
        }
        if (!downloadFile.renameTo(cacheFile)) {
            throw new IOException("Cannot replace " + cacheFile);
        }
        return directory;
    }

    private static void downloadTo(File target) throws IOException {
        HttpURLConnection connection = Requester.openConnection(DUMP_URL);
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new IOException("SoundProof dump download failed: HTTP " + responseCode);
            }
            try (InputStream body = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                copy(body, output);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
