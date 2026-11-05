/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;

/**
 * Mandarin word readings in Bopomofo from McBopomofo, which knows words the {@link UniDictStore}
 * character tables do not; character readings come from {@link UniDictStore#bopomofoReading(char)},
 * this store only adds how a word is read. Downloaded, since the patch merges only XML resources
 * and a word list goes stale as the source moves on.
 */
public final class BopomofoDictStore {

    public static final String VERSION = "1.0.0";

    /**
     * The commit is what names the version: the file is updated in place, and the table
     * has to be rebuilt when it changes.
     */
    private static final String WORDS_REVISION = "ccd2cd7892c93301b23e5f5ffe8945f0a3765088";
    private static final String WORDS_URL =
            "https://raw.githubusercontent.com/openvanilla/McBopomofo/" + WORDS_REVISION
                    + "/Source/Data/BPMFMappings.txt";
    private static final String WORDS_ARCHIVE = "bopomofo_words.txt";
    private static final WordTableFile WORDS = new WordTableFile(
            "bopomofo_words_" + WORDS_REVISION + ".bin",
            new byte[]{'M', 'B', 'P', 'F', '0', '0', '0', '1'}, 1 << 20);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-bopomofo-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();

    private BopomofoDictStore() {
    }

    /**
     * Loads on first use; the character tables must be there too, or per-character readings
     * a word table does not cover would be missing.
     */
    public static boolean isReady() {
        return UniDictStore.isReady() && word() != null;
    }

    /**
     * Removes the word table. The character table is {@link UniDictStore}'s to remove, and is
     * left alone.
     */
    public static void delete() {
        WORDS.clear();
        File file = WORDS.file();
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** The reading of a character, delegated to the shared character tables. */
    @Nullable
    public static String reading(char c) {
        return UniDictStore.bopomofoReading(c);
    }

    /** Null leaves the line to be read a character at a time. */
    @Nullable
    public static WordReadingTable word() {
        return WORDS.open();
    }

    /**
     * Whether the word table has been built; the character tables are this store's by way of
     * {@link UniDictStore}, and are checked through {@link UniDictStore#isReady()}.
     */
    public static boolean isComplete() {
        return isReady();
    }

    /** Starts a fetch if the table is not loaded. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isComplete()) {
            listener.onReady(entries());
            return;
        }
        CHANNEL.attach(listener, RUNNING.get());
        if (!RUNNING.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            try {
                build(CHANNEL);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Bopomofo dictionary build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    private static int entries() {
        WordReadingTable words = word();
        return words == null ? 0 : words.size();
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        WORDS.build(directory, WORDS_URL, WORDS_ARCHIVE, BopomofoDictStore::addWords, listener);
        listener.onReady(entries());
    }

    /**
     * Reads a word list into the table. Each line is a word followed by its Bopomofo readings,
     * one syllable per character, separated by spaces. The first reading of a word wins, since
     * the list is sorted by frequency.
     */
    private static void addWords(File archive, WordReadingTable.Builder builder,
                                 RomanizationDict.Listener listener) throws IOException {
        int seen = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16),
                StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;

                int firstSpace = line.indexOf(' ');
                if (firstSpace < 0) continue;

                String word = line.substring(0, firstSpace);
                String reading = line.substring(firstSpace + 1).trim();
                if (word.isEmpty() || reading.isEmpty()) continue;

                builder.put(word, reading);
                if (++seen % WordTableFile.PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WordTableFile.PROGRESS_EVERY));
                }
            }
            listener.onBuilding(100);
        }
    }
}
