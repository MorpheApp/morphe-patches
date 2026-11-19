/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Downloads, decompresses, and packs the lightweight (~17 MB) Kuromoji IPADIC morphological
 * dictionary ({@code kuromoji@0.1.2}) into a single memory-mapped file for offline Japanese
 * romanization and furigana ruby generation.
 */
public final class KuromojiDictStore {

    public static final String VERSION = "0.1.2";

    private static final String PRIMARY_BASE_URL =
            "https://cdn.jsdelivr.net/npm/kuromoji@" + VERSION + "/dict/";
    private static final String FALLBACK_BASE_URL =
            "https://unpkg.com/kuromoji@" + VERSION + "/dict/";

    /** Cap each compressed file well above base.dat.gz (~4.7 MB) to guard against runaway streams. */
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;

    private static final String PACKED_FILE_NAME = "kuromoji_ipadic_v1.bin";

    private static final String[] FILES = {
            "base.dat.gz",
            "check.dat.gz",
            "tid.dat.gz",
            "tid_pos.dat.gz",
            "tid_map.dat.gz",
            "cc.dat.gz",
            "unk.dat.gz",
            "unk_pos.dat.gz",
            "unk_map.dat.gz",
            "unk_char.dat.gz",
            "unk_compat.dat.gz",
            "unk_invoke.dat.gz"
    };

    /** Pinned SHA-256 hex digests of each decompressed IPADIC {@code .dat} table ({@code kuromoji@0.1.2}). */
    private static final String[] RAW_SHA256 = {
            "e578a6c02ed92346df566306aadfed835f089b39750e8dafe5abf90a966d6297", // base.dat
            "414c6a284a779632d23ff86f34761c1a5c091baa1cfcea1b733d82fa7ac89cbc", // check.dat
            "ed8977acc3980dd7436e3975e2183f8312dad22a3b45f40e368309805d3ac095", // tid.dat
            "a0fa608406f1e8a445b2810499878a5e69234b1ebebb2ce6c66a950749c8a9f2", // tid_pos.dat
            "def2c940677db713a000fed2eb6dca9e97417240966e2298d6c03dc118016e4a", // tid_map.dat
            "4ebebb0e6b4b17aa0f25cc665536c2ecd3caeaab347d0cb519b3b1bb5e8d650f", // cc.dat
            "a6e35190d998440fc62191f7f95cb6b9f078fd236abed8f88ebe12916ae0fbd5", // unk.dat
            "d8771fad9c3329a674e318f498252eda032c5e3c2ee63192bae0df8d78cc043d", // unk_pos.dat
            "15619220f521c5ce8be864d3cd1fec2bb01ac78efbea958100b6334222150e9a", // unk_map.dat
            "218e90b85ece7734cfd1d10a3d3153083ff7899693060755985095d365663793", // unk_char.dat
            "b9f3cb34710353434dfc623300bcad22b622895dca3195af49e85d3de2438214", // unk_compat.dat
            "e9842ac062646d8da0e6728483eaeed58ee5518d2b6313d393c4fa4c16e84dbd"  // unk_invoke.dat
    };

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-kuromoji-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final RomanizationDictionary.Channel CHANNEL = new RomanizationDictionary.Channel();

    @Nullable
    private static volatile KuromojiTokenizer cachedTokenizer;

    /** Optional test hook allowing unit tests to supply a synthetic tokenizer. */
    @Nullable
    private static volatile KuromojiTokenizer testOverrideTokenizer;

    private KuromojiDictStore() {
    }

    static void setTestTokenizer(@Nullable KuromojiTokenizer tokenizer) {
        testOverrideTokenizer = tokenizer;
    }

    @Nullable
    private static File packedFile() {
        File dir = RomanizationDictionary.directory();
        return dir != null ? new File(dir, PACKED_FILE_NAME) : null;
    }

    public static boolean isReady() {
        return tokenizer() != null;
    }

    /** Removes the packed Kuromoji dictionary from disk and memory. */
    public static void delete() {
        cachedTokenizer = null;
        File file = packedFile();
        if (file != null && file.exists()) {
            Utils.deleteQuietly(file);
        }
    }

    /** Starts downloading and packing Kuromoji if missing. Safe to call repeatedly. */
    public static void ensure(RomanizationDictionary.Listener listener) {
        KuromojiTokenizer ready = tokenizer();
        if (ready != null) {
            listener.onReady(ready.dictionary().size());
            return;
        }
        CHANNEL.attach(listener, RUNNING.get());
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }

        WORKER.execute(() -> {
            try {
                build();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Kuromoji dictionary build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    /** Returns the ready {@link KuromojiTokenizer}, opening the memory-mapped file on first use. */
    @Nullable
    public static KuromojiTokenizer tokenizer() {
        KuromojiTokenizer testOverride = testOverrideTokenizer;
        if (testOverride != null) {
            return testOverride;
        }
        KuromojiTokenizer current = cachedTokenizer;
        if (current != null) {
            return current;
        }
        File file = packedFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        synchronized (KuromojiDictStore.class) {
            if (cachedTokenizer != null) {
                return cachedTokenizer;
            }
            try {
                KuromojiDictionary dict = KuromojiDictionary.open(file);
                cachedTokenizer = new KuromojiTokenizer(dict);
                return cachedTokenizer;
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not open packed Kuromoji dictionary, deleting corrupt copy", ex);
                Utils.deleteQuietly(file);
                return null;
            }
        }
    }

    private static void build() throws IOException {
        File directory = RomanizationDictionary.directory();
        if (directory == null) {
            throw new IOException("no cache directory");
        }
        File target = packedFile();
        if (target == null) {
            throw new IOException("no target file");
        }

        File built = new File(target.getPath() + ".building");
        Utils.deleteQuietly(built);

        File[] rawFiles = new File[FILES.length];
        try {
            for (int i = 0; i < FILES.length; i++) {
                String fileName = FILES[i];
                File archive = new File(directory, "kuromoji_" + fileName);
                File rawFile = new File(directory, "kuromoji_" + i + ".raw");
                Utils.deleteQuietly(rawFile);
                String label = displayName(fileName);
                if (!archive.isFile()) {
                    downloadWithFallback(fileName, archive, label);
                }
                CHANNEL.onBuilding((i * 85) / FILES.length);
                try {
                    decompressToFile(archive, rawFile, RAW_SHA256[i]);
                } catch (IOException corruptEx) {
                    // Re-download from fallback mirror if cached partial/corrupt archive failed gunzip or checksum.
                    Utils.deleteQuietly(archive);
                    Utils.deleteQuietly(rawFile);
                    RomanizationDictionary.download(FALLBACK_BASE_URL + fileName, archive, label,
                            CHANNEL, MAX_FILE_BYTES);
                    decompressToFile(archive, rawFile, RAW_SHA256[i]);
                }
                Utils.deleteQuietly(archive);
                rawFiles[i] = rawFile;
            }

            CHANNEL.onBuilding(90);
            int entries = KuromojiDictionary.writePackedFromFiles(built, rawFiles);

            CHANNEL.onBuilding(100);
            RomanizationDictionary.commit(built, target);
            synchronized (KuromojiDictStore.class) {
                cachedTokenizer = new KuromojiTokenizer(KuromojiDictionary.open(target));
            }
            CHANNEL.onReady(entries);
        } finally {
            for (File raw : rawFiles) {
                if (raw != null) {
                    Utils.deleteQuietly(raw);
                }
            }
            Utils.deleteQuietly(built);
        }
    }

    private static void downloadWithFallback(String fileName, File archive, String label)
            throws IOException {
        try {
            RomanizationDictionary.download(PRIMARY_BASE_URL + fileName, archive, label,
                    CHANNEL, MAX_FILE_BYTES);
        } catch (IOException primaryEx) {
            Utils.deleteQuietly(archive);
            Logger.printDebug(() -> "Primary Kuromoji CDN failed for " + fileName
                    + ", trying mirror", primaryEx);
            RomanizationDictionary.download(FALLBACK_BASE_URL + fileName, archive, label,
                    CHANNEL, MAX_FILE_BYTES);
        }
    }

    /**
     * Transparently handles both raw gzipped {@code .dat.gz} streams and streams already
     * decompressed by an HTTP proxy/CDN (checks GZIP magic {@code 0x1F 0x8B}), streaming
     * directly to disk with bounded heap usage and verifying the decompressed SHA-256 digest.
     */
    static void decompressToFile(File srcArchive, File destRaw, @Nullable String expectedSha256)
            throws IOException {
        MessageDigest digest = expectedSha256 != null ? sha256Digest() : null;
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(srcArchive), 1 << 15)) {
            bis.mark(4);
            int b0 = bis.read();
            int b1 = bis.read();
            bis.reset();
            boolean isGzip = (b0 == 0x1F && b1 == 0x8B);
            try (InputStream in = isGzip ? new GZIPInputStream(bis, 1 << 15) : bis;
                 OutputStream out = new FileOutputStream(destRaw)) {
                byte[] buffer = new byte[1 << 15];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (digest != null) {
                        digest.update(buffer, 0, read);
                    }
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
        if (digest != null) {
            String actualHex = toHex(digest.digest());
            if (!actualHex.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
                Utils.deleteQuietly(destRaw);
                throw new IOException("SHA-256 mismatch for " + destRaw.getName());
            }
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static String displayName(String fileName) {
        if (fileName.endsWith(".dat.gz")) {
            return "kuromoji/" + fileName.substring(0, fileName.length() - 7);
        }
        return "kuromoji/" + fileName;
    }
}
