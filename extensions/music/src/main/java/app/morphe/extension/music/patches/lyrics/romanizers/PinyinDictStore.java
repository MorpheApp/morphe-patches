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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.morphe.extension.shared.Logger;

/**
 * The Mandarin reading of every Han character, taken from the Unicode Han Database, which
 * publishes one reading per character: the one it is given when no word says otherwise. That is
 * the right starting point for a lyric, since the readings that differ belong to words, and those
 * come from {@link #word()} instead.
 *
 * <p>Downloaded rather than shipped, because the patch can only merge XML resources and because
 * a table of every character goes stale as soon as the database moves on.
 */
public final class PinyinDictStore {

    public static final String VERSION = "18.0.0";

    private static final String URL =
            "https://www.unicode.org/Public/" + VERSION + "/ucd/Unihan.zip";
    private static final String ARCHIVE = "Unihan.zip";
    private static final String READINGS_ENTRY = "Unihan_Readings.txt";
    private static final String TABLE_NAME = "pinyin_readings_toned_" + VERSION + ".txt";
    private static final String NOTICE_NAME = "pinyin_readings.NOTICE.txt";

    /** The commit is what names the version: the file is updated in place, and the table
     * has to be rebuilt when it changes. */
    private static final String WORDS_REVISION = "b0b2c2f93be09e76b8970db1563e943032bfb805";
    private static final String WORDS_URL =
            "https://raw.githubusercontent.com/chinese-data/pinyin-pro-data/" + WORDS_REVISION
                    + "/data/complete.json";
    private static final String WORDS_ARCHIVE = "pinyin_words.json";
    private static final String WORDS_NOTICE_NAME = "pinyin_words.NOTICE.txt";
    private static final WordTableFile WORDS = new WordTableFile(
            "pinyin_words_" + WORDS_REVISION + ".bin",
            new byte[] {'M', 'P', 'Y', 'W', '0', '0', '0', '1'}, 1 << 20);

    /** How much a reading field is trusted, best first. */
    private static final String[] SOURCES =
            {"kMandarin", "kHanyuPinlu", "kHanyuPinyin", "kXHC1983"};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-pinyin-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();

    @Nullable
    private static volatile CharReadingTable characters;

    private PinyinDictStore() {
    }

    /** Loads the table on first use, since only the settings row downloads it. */
    public static boolean isReady() {
        if (characters != null) return true;
        File table = tableFile();
        if (table == null || !table.isFile()) return false;
        try {
            characters = CharReadingTable.load(table);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the pinyin table", ex);
            return false;
        }
        return true;
    }

    @Nullable
    private static File tableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, TABLE_NAME);
    }

    /** The reading of a character, or null when the database has none for it. */
    @Nullable
    public static String reading(char c) {
        CharReadingTable table = characters;
        return table == null ? null : table.reading(c);
    }

    /** Null leaves the line to be read a character at a time. */
    @Nullable
    public static WordReadingTable word() {
        return WORDS.open();
    }

    /** Starts a fetch if the table is not loaded. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isReady() && word() != null && CantoneseDictStore.word() != null) {
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
        return directory == null ? null : RomanizationDict.readNotice(directory, NOTICE_NAME);
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        File table = tableFile();
        if (!table.isFile()) {
            File archive = new File(directory, ARCHIVE);
            if (!archive.isFile()) {
                RomanizationDict.download(URL, archive, ARCHIVE, listener);
            }
            extract(archive, table, listener);
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }

        characters = CharReadingTable.load(table);
        RomanizationDict.writeNotice(directory, NOTICE_NAME, noticeText());
        CantoneseDictStore.writeNotice(directory);

        WORDS.build(directory, WORDS_URL, WORDS_ARCHIVE, PinyinDictStore::addWords, listener);
        RomanizationDict.writeNotice(directory, WORDS_NOTICE_NAME, wordsNoticeText());
        CantoneseDictStore.buildWords(directory, listener);
        listener.onReady(entries());
    }

    private static int entries() {
        WordReadingTable pinyinWords = word();
        WordReadingTable cantoneseWords = CantoneseDictStore.word();
        return characters.size()
                + (pinyinWords == null ? 0 : pinyinWords.size())
                + (cantoneseWords == null ? 0 : cantoneseWords.size());
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

    /** The erhua suffix rides on the syllable before it, since such a word has one syllable
     * fewer than it has characters. */
    private static String withErhua(String reading) {
        int cut = reading.lastIndexOf(' ');
        if (cut < 0 || !"er".equals(reading.substring(cut + 1))) return reading;
        return reading.substring(0, cut) + "r";
    }

    /**
     * Reads the readings out of the archive and writes one line per character, holding only
     * the candidates of the character being read: taking the first line instead would pick
     * kHanyuPinlu, which is the poorer source and comes first alphabetically.
     */
    private static void extract(File archive, File table,
            RomanizationDict.Listener listener) throws IOException {
        File built = new File(table.getPath() + ".building");
        File cantoneseTable = CantoneseDictStore.tableFile();
        File cantoneseBuilt = new File(cantoneseTable.getPath() + ".building");
        long size = archive.length();

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             Writer out = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(built), StandardCharsets.UTF_8), 1 << 16);
             Writer cantoneseOut = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(cantoneseBuilt), StandardCharsets.UTF_8), 1 << 16)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !READINGS_ENTRY.equals(entry.getName())) continue;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zip, StandardCharsets.UTF_8), 1 << 16);
                StringBuilder pending = new StringBuilder();
                char pendingChar = 0;
                int pendingRank = SOURCES.length;

                String line;
                long done = 0;
                while ((line = reader.readLine()) != null) {
                    done += line.length();

                    int firstTab = line.indexOf('\t');
                    int secondTab = firstTab < 0 ? -1 : line.indexOf('\t', firstTab + 1);
                    if (secondTab < 0) continue;

                    String field = line.substring(firstTab + 1, secondTab);
                    char c = codePoint(line.substring(0, firstTab));
                    if (c == 0) continue;

                    // The archive is deleted once unpacked, so the Cantonese table leaves in this same pass.
                    if (CantoneseDictStore.FIELD.equals(field)) {
                        String syllable = first(line.substring(secondTab + 1));
                        if (syllable != null) {
                            cantoneseOut.append(c).append(' ').append(syllable).append('\n');
                        }
                        continue;
                    }

                    int rank = rank(field);
                    if (rank < 0) continue;

                    if (c != pendingChar) {
                        out.append(pending);
                        pending.setLength(0);
                        pendingChar = c;
                        pendingRank = SOURCES.length;
                    }
                    if (rank >= pendingRank) continue;

                    String reading = first(line.substring(secondTab + 1));
                    if (reading != null) {
                        pending.append(c).append(' ').append(reading).append('\n');
                        pendingRank = rank;
                    }

                    if (size > 0 && done % (1 << 20) < line.length()) {
                        listener.onBuilding((int) Math.min(100, done * 100 / size));
                    }
                }
                out.append(pending);
                listener.onBuilding(100);
            }
        }
        RomanizationDict.commit(built, table);
        RomanizationDict.commit(cantoneseBuilt, cantoneseTable);
    }

    private static int rank(String field) {
        for (int i = 0; i < SOURCES.length; i++) {
            if (SOURCES[i].equals(field)) return i;
        }
        return -1;
    }

    private static char codePoint(String field) {
        if (field.length() < 3 || field.charAt(0) != 'U' || field.charAt(1) != '+') return 0;
        try {
            int value = Integer.parseInt(field.substring(2), 16);
            return value > 0 && value <= Character.MAX_VALUE ? (char) value : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** The first reading of a field, minus its frequency or location code, as in {@code wo(23213)}. */
    @Nullable
    private static String first(String value) {
        for (String candidate : value.split(",")) {
            String reading = candidate.trim();
            int cut = reading.indexOf(':');
            if (cut >= 0) reading = reading.substring(cut + 1).trim();
            cut = reading.indexOf('(');
            if (cut >= 0) reading = reading.substring(0, cut).trim();
            cut = reading.indexOf(' ');
            if (cut > 0) reading = reading.substring(0, cut);
            if (reading.length() > 0) return reading.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String noticeText() {
        return noticeTextFor("kMandarin");
    }

    static String noticeTextFor(String field) {
        return "Unicode Han Database readings\n"
                + "https://www.unicode.org/Public/" + VERSION + "/ucd/Unihan.zip\n\n"
                + "Licence: Unicode License, Version 3\n"
                + "https://www.unicode.org/license.txt\n\n"
                + "Copyright (c) 1991-2026 Unicode, Inc. All rights reserved.\n"
                + "Distributed under the Terms of Use in https://www.unicode.org/copyright.txt\n\n"
                + "What is stored here is the " + field + " reading of each character, taken from\n"
                + "Unihan_Readings.txt in the file above.\n";
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

    @Nullable
    public static String wordsNotice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null
                : RomanizationDict.readNotice(directory, WORDS_NOTICE_NAME);
    }
}
