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
 * One reading per Han character, from the Unicode Han Database, for each language that reads a
 * Han lyric; one download serves every table. Each {@link #TABLES} row is a language by its
 * BCP-47 tag, its Unihan fields best first; turning this off removes the character tables, so a
 * language falls back to its word tables alone.
 */
public final class UniDictStore {

    /** The Unicode release the tables were built from. */
    public static final String VERSION = "18.0.0";

    private static final String URL =
            "https://www.unicode.org/Public/" + VERSION + "/ucd/Unihan.zip";
    private static final String ARCHIVE = "Unihan.zip";
    private static final String READINGS_ENTRY = "Unihan_Readings.txt";
    /** The languages whose character readings are extracted. */
    private static final ReadingTable[] TABLES = {
            new ReadingTable("zh",
                    new String[]{"kMandarin", "kHanyuPinlu", "kHanyuPinyin", "kXHC1983"},
                    "kMandarin", "pinyin_readings_toned_" + VERSION + ".txt",
                    "pinyin_readings.NOTICE.txt"),
            new ReadingTable("yue",
                    new String[]{"kCantonese"},
                    "kCantonese", "cantonese_readings_" + VERSION + ".txt",
                    "cantonese_readings.NOTICE.txt"),
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-unidict");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();

    private UniDictStore() {
    }

    /** Whether the tables are being downloaded or built right now. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** Loads the tables on first use, since only the settings row downloads them. */
    public static boolean isReady() {
        for (ReadingTable table : TABLES) {
            if (table(table) == null) return false;
        }
        return true;
    }

    /** Removes every character table and its notice; the user turned the readings off. */
    public static void delete() {
        for (ReadingTable table : TABLES) {
            table.table = null;
        }
        File directory = RomanizationDict.directory();
        if (directory == null) return;
        for (ReadingTable table : TABLES) {
            //noinspection ResultOfMethodCallIgnored
            new File(directory, table.tableName).delete();
            //noinspection ResultOfMethodCallIgnored
            new File(directory, table.noticeName).delete();
        }
        //noinspection ResultOfMethodCallIgnored
        new File(directory, ARCHIVE).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, ARCHIVE + ".part").delete();
    }

    /** Starts a build if any table is missing. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isReady()) {
            listener.onReady(entries());
            return;
        }
        CHANNEL.attach(listener, RUNNING.get());
        if (!RUNNING.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            try {
                build(CHANNEL);
            } catch (Exception ex) {
                Logger.printDebug(() -> "UniDict build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    /** The Mandarin reading of a character, or null when the database has none for it. */
    @Nullable
    public static String pinyinReading(char c) {
        return reading("zh", c);
    }

    /** The Cantonese reading of a character, or null when the database has none for it. */
    @Nullable
    public static String cantoneseReading(char c) {
        return reading("yue", c);
    }

    /** The reading from the table already loaded, or null to leave the character alone. */
    @Nullable
    private static String reading(String code, char c) {
        ReadingTable table = tableFor(code);
        if (table == null) return null;
        CharReadingTable loaded = table.table;
        return loaded == null ? null : loaded.reading(c);
    }

    private static int entries() {
        int entries = 0;
        for (ReadingTable table : TABLES) {
            CharReadingTable loaded = table.table;
            if (loaded != null) entries += loaded.size();
        }
        return entries;
    }

    /** The table of a registered language, loading it if the file is there. */
    @Nullable
    private static CharReadingTable table(ReadingTable table) {
        CharReadingTable loaded = table.table;
        if (loaded != null) return loaded;
        File file = tableFile(table.tableName);
        if (file == null || !file.isFile()) return null;
        try {
            loaded = CharReadingTable.load(file);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the " + table.code + " table", ex);
            return null;
        }
        table.table = loaded;
        return loaded;
    }

    @Nullable
    private static ReadingTable tableFor(String code) {
        for (ReadingTable table : TABLES) {
            if (table.code.equals(code)) return table;
        }
        return null;
    }

    @Nullable
    private static File tableFile(String name) {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, name);
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        boolean missing = false;
        for (ReadingTable table : TABLES) {
            File file = tableFile(table.tableName);
            if (file != null && file.isFile()) {
                table.table = CharReadingTable.load(file);
            } else {
                missing = true;
            }
        }
        if (!missing) {
            listener.onReady(entries());
            return;
        }

        File archive = tableFile(ARCHIVE);
        if (archive == null) throw new IOException("no cache directory");
        if (!archive.isFile()) {
            RomanizationDict.download(URL, archive, ARCHIVE, listener);
        }
        extract(archive, listener);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        for (ReadingTable table : TABLES) {
            RomanizationDict.writeNotice(directory, table.noticeName,
                    noticeTextFor(table.noticeField));
        }
        listener.onReady(entries());
    }

    /**
     * Reads the readings out of the archive, one line per character per language. A language
     * that reads one field is written straight out; one that reads several keeps the best
     * candidate of the character being read, since taking the first line instead would pick
     * kHanyuPinlu, which is the poorer source and comes first alphabetically.
     */
    private static void extract(File archive, RomanizationDict.Listener listener)
            throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        File[] built = new File[TABLES.length];
        File[] targets = new File[TABLES.length];
        Writer[] writers = new Writer[TABLES.length];
        try {
            for (int i = 0; i < TABLES.length; i++) {
                File target = tableFile(TABLES[i].tableName);
                built[i] = new File(target.getPath() + ".building");
                targets[i] = target;
                writers[i] = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(built[i]), StandardCharsets.UTF_8), 1 << 16);
            }
            long size = archive.length();

            // The best candidate of the character being read, one state per table.
            StringBuilder[] pending = new StringBuilder[TABLES.length];
            char[] pendingChar = new char[TABLES.length];
            int[] pendingRank = new int[TABLES.length];
            for (int i = 0; i < TABLES.length; i++) {
                pendingRank[i] = TABLES[i].sources.length;
            }

            try (ZipInputStream zip = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(archive), 1 << 16))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !READINGS_ENTRY.equals(entry.getName())) continue;

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(zip, StandardCharsets.UTF_8), 1 << 16);
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

                        int row = row(field);
                        if (row < 0) continue;

                        ReadingTable table = TABLES[row];
                        if (table.sources.length == 1) {
                            String syllable = first(line.substring(secondTab + 1));
                            if (syllable != null) {
                                writers[row].append(c).append(' ').append(syllable).append('\n');
                            }
                            continue;
                        }

                        int rank = rank(table, field);
                        if (rank < 0) continue;

                        StringBuilder held = pending[row];
                        if (held == null) {
                            held = new StringBuilder();
                            pending[row] = held;
                        }
                        if (c != pendingChar[row]) {
                            writers[row].append(held);
                            held.setLength(0);
                            pendingChar[row] = c;
                            pendingRank[row] = table.sources.length;
                        }
                        if (rank >= pendingRank[row]) continue;

                        String reading = first(line.substring(secondTab + 1));
                        if (reading != null) {
                            held.append(c).append(' ').append(reading).append('\n');
                            pendingRank[row] = rank;
                        }

                        if (size > 0 && done % (1 << 20) < line.length()) {
                            listener.onBuilding((int) Math.min(100, done * 100 / size));
                        }
                    }
                    for (int i = 0; i < TABLES.length; i++) {
                        if (pending[i] != null) writers[i].append(pending[i]);
                    }
                    listener.onBuilding(100);
                }
            }
        } finally {
            for (Writer writer : writers) {
                if (writer != null) writer.close();
            }
        }

        for (int i = 0; i < TABLES.length; i++) {
            RomanizationDict.commit(built[i], targets[i]);
        }
    }

    /** The row of {@link #TABLES} a reading field belongs to, or -1 when it feeds none. */
    private static int row(String field) {
        for (int i = 0; i < TABLES.length; i++) {
            for (String source : TABLES[i].sources) {
                if (source.equals(field)) return i;
            }
        }
        return -1;
    }

    /** How much a reading field is trusted within its table, best first. */
    private static int rank(ReadingTable table, String field) {
        for (int i = 0; i < table.sources.length; i++) {
            if (table.sources[i].equals(field)) return i;
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
            //noinspection SizeReplaceableByIsEmpty
            if (reading.length() > 0) return reading.toLowerCase(Locale.ROOT);
        }
        return null;
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

    @Nullable
    public static String notice() {
        File directory = RomanizationDict.directory();
        if (directory == null) return null;
        StringBuilder text = new StringBuilder();
        for (ReadingTable table : TABLES) {
            String part = RomanizationDict.readNotice(directory, table.noticeName);
            if (part == null) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(part);
        }
        return text.length() > 0 ? text.toString() : null;
    }

    /**
     * One language's character readings: the Unihan fields that say them, best first, and
     * what the table is stored and shown as.
     */
    private static final class ReadingTable {
        final String code;
        final String[] sources;
        final String noticeField;
        final String tableName;
        final String noticeName;

        @Nullable
        volatile CharReadingTable table;

        ReadingTable(String code, String[] sources, String noticeField, String tableName,
                     String noticeName) {
            this.code = code;
            this.sources = sources;
            this.noticeField = noticeField;
            this.tableName = tableName;
            this.noticeName = noticeName;
        }
    }
}
