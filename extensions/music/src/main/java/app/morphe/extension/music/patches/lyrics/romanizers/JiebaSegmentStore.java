/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** The jieba word/frequency table, packed like the reading tables. */
public final class JiebaSegmentStore {

    private static final String REVISION = "67fa2e36e72f69d9134b8a1037b83fbb070b9775";
    private static final String URL = "https://raw.githubusercontent.com/fxsjy/jieba/"
            + REVISION + "/jieba/dict.txt";
    private static final String ARCHIVE = "jieba_dict.txt";
    static final byte[] MAGIC = {'M', 'P', 'J', 'B', '0', '0', '0', '1'};
    private static final WordTableFile WORDS = new WordTableFile(
            "jieba_words_" + REVISION.substring(0, 12) + ".bin", MAGIC, 1 << 20);
    private static final long MAX_BYTES = 16L << 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-jieba-dict");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final RomanizationDictionary.Channel CHANNEL = new RomanizationDictionary.Channel();

    private JiebaSegmentStore() {
    }

    public static boolean isReady() {
        return words() != null;
    }

    @Nullable
    public static WordReadingTable words() {
        return WORDS.open();
    }

    public static void delete() {
        WORDS.clear();
        File file = WORDS.file();
        if (file != null && file.exists()) {
            Utils.deleteQuietly(file);
        }
    }

    public static void ensure(RomanizationDictionary.Listener listener) {
        if (isReady()) {
            listener.onReady(0);
            return;
        }
        CHANNEL.attach(listener, RUNNING.get());
        if (!RUNNING.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            try {
                build();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Jieba dictionary build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    private static void build() throws IOException {
        File directory = RomanizationDictionary.directory();
        if (directory == null) throw new IOException("no cache directory");

        WORDS.build(directory, URL, ARCHIVE, JiebaSegmentStore::addWords, CHANNEL, MAX_BYTES);
        try (WordReadingTable table = words()) {
            CHANNEL.onReady(table == null ? 0 : table.size());
        }
    }

    private static void addWords(File archive, WordReadingTable.Builder builder,
                                 RomanizationDictionary.Listener listener) throws IOException {
        int seen = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(archive), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                final StringTokenizer parts = new StringTokenizer(line, " \t");
                if (parts.countTokens() < 2) continue;
                final String word = parts.nextToken();
                final String frequency = parts.nextToken();
                if (word.isEmpty() || frequency.isEmpty()) continue;
                builder.put(word, frequency);
                if (++seen % WordTableFile.PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WordTableFile.PROGRESS_EVERY));
                }
            }
        }
        listener.onBuilding(100);
    }
}
