/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
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
 * Mandarin word readings from pinyin-pro, which knows words the {@link UniDictStore} character
 * tables do not; character readings come from {@link UniDictStore#pinyinReading(char)}, this
 * store only adds how a word is read. Downloaded, since the patch merges only XML resources and
 * a word list goes stale as the source moves on.
 */
public final class PinyinDictStore {

    public static final String VERSION = "18.0.0";

    /**
     * The commit is what names the version: the file is updated in place, and the table
     * has to be rebuilt when it changes.
     */
    private static final String WORDS_REVISION = "b0b2c2f93be09e76b8970db1563e943032bfb805";
    private static final String WORDS_URL =
            "https://raw.githubusercontent.com/chinese-data/pinyin-pro-data/" + WORDS_REVISION
                    + "/data/complete.json";
    private static final String WORDS_ARCHIVE = "pinyin_words.json";
    private static final String WORDS_NOTICE_NAME = "pinyin_words.NOTICE.txt";
    private static final WordTableFile WORDS = new WordTableFile(
            "pinyin_words_" + WORDS_REVISION + ".bin",
            new byte[]{'M', 'P', 'Y', 'W', '0', '0', '0', '1'}, 1 << 20);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-pinyin-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();

    private PinyinDictStore() {
    }

    /**
     * Loads on first use; the character tables must be there too, or per-character readings
     * a word table does not cover would be missing.
     */
    public static boolean isReady() {
        return UniDictStore.isReady() && word() != null;
    }

    /**
     * Removes the word table and its licence notice. The character table is
     * {@link UniDictStore}'s to remove, and is left alone.
     */
    public static void delete() {
        WORDS.clear();
        File file = WORDS.file();
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File directory = RomanizationDict.directory();
        if (directory != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(directory, WORDS_NOTICE_NAME).delete();
        }
    }

    /** The reading of a character, delegated to the shared character tables. */
    @Nullable
    public static String reading(char c) {
        return UniDictStore.pinyinReading(c);
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
                Logger.printDebug(() -> "Pinyin dictionary build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    @Nullable
    public static String notice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : RomanizationDict.readNotice(directory, WORDS_NOTICE_NAME);
    }

    private static int entries() {
        WordReadingTable words = word();
        return words == null ? 0 : words.size();
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        WORDS.build(directory, WORDS_URL, WORDS_ARCHIVE, PinyinDictStore::addWords, listener);
        RomanizationDict.writeNotice(directory, WORDS_NOTICE_NAME, wordsNoticeText());
        listener.onReady(entries());
    }

    /**
     * Read rather than parsed: a quote followed by a colon appears in a word and nowhere else,
     * which is enough to tell a key from a reading however the file happens to be laid out.
     */
    private static void addWords(File archive, WordReadingTable.Builder builder,
                                 RomanizationDict.Listener listener) throws IOException {
        String word = null;
        String reading = null;
        int seen = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16),
                StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int colon = line.indexOf("\":");
                if (colon > 0) {
                    word = quoted(line, 0);
                    reading = quoted(line, line.indexOf('[', colon));
                } else if (word != null && reading == null) {
                    // A reading long enough to be wrapped onto its own line.
                    reading = quoted(line, 0);
                }
                if (word == null || reading == null) continue;

                builder.put(word, withErhua(reading));
                if (++seen % WordTableFile.PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WordTableFile.PROGRESS_EVERY));
                }
                word = null;
                reading = null;
            }
            listener.onBuilding(100);
        }
    }

    @Nullable
    private static String quoted(String line, int from) {
        if (from < 0) return null;
        int open = line.indexOf('"', from);
        if (open < 0) return null;
        int close = line.indexOf('"', open + 1);
        return close < 0 ? null : line.substring(open + 1, close);
    }

    /**
     * The erhua suffix rides on the syllable before it, since such a word has one syllable
     * fewer than it has characters.
     */
    private static String withErhua(String reading) {
        int cut = reading.lastIndexOf(' ');
        if (cut < 0 || !"er".equals(reading.substring(cut + 1))) return reading;
        return reading.substring(0, cut) + "r";
    }

    private static String wordsNoticeText() {
        return "pinyin-pro word readings\n"
                + WORDS_URL + "\n\n"
                + "From https://github.com/chinese-data/pinyin-pro-data\n\n"
                + "MIT License\n\n"
                + "Copyright (c) 2024 Chinese-Data\n\n"
                + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
                + "of this software and associated documentation files (the \"Software\"), to deal\n"
                + "in the Software without restriction, including without limitation the rights\n"
                + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
                + "copies of the Software, and to permit persons to whom the Software is\n"
                + "furnished to do so, subject to the following conditions:\n\n"
                + "The above copyright notice and this permission notice shall be included in all\n"
                + "copies or substantial portions of the Software.\n\n"
                + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
                + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
                + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
                + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
                + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
                + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
                + "SOFTWARE.\n\n"
                + "What is stored here is the reading of each word, taken from data/complete.json\n"
                + "in the file above, with the erhua suffix folded into the syllable before it.\n";
    }
}
