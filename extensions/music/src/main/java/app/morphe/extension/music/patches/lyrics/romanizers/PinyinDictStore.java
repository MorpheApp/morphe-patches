/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.morphe.extension.shared.Logger;

/**
 * The Mandarin reading of every Han character, taken from the Unicode Han Database, which
 * publishes one reading per character: the one it is given when no word says otherwise. That is
 * the right starting point for a lyric, since the words wanting another are few and are listed
 * by hand in {@link PinyinRomanizer}.
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
    private static final String TABLE_NAME = "pinyin_readings_" + VERSION + ".txt";
    private static final String NOTICE_NAME = "pinyin_readings.NOTICE.txt";

    /** How much a reading field is trusted, best first. */
    private static final String[] SOURCES =
            {"kMandarin", "kHanyuPinlu", "kHanyuPinyin", "kXHC1983"};

    /**
     * The vowels with their tones, and what each reduces to, written as escapes because a
     * mistyped literal silently changes every reading that uses it.
     */
    private static final String TONES = "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿêếề";
    private static final String UNTONED =
            "aaaa" + "eeee" + "iiii" + "oooo" + "uuuu" + "üüüü" + "nnn" + "m" + "eee";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-pinyin-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static volatile char[] keys;
    private static volatile String[] values;

    private PinyinDictStore() {
    }

    /** Loads the table on first use, since only the settings row downloads it. */
    public static boolean isReady() {
        if (keys != null) return true;
        File table = tableFile();
        if (table == null || !table.isFile()) return false;
        try {
            load(table);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the pinyin table", ex);
            return false;
        }
        return keys != null;
    }

    @Nullable
    private static File tableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, TABLE_NAME);
    }

    /** The reading of a character, or null when the database has none for it. */
    @Nullable
    public static String reading(char c) {
        char[] table = keys;
        if (table == null) return null;

        int low = 0;
        int high = table.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (table[middle] < c) {
                low = middle + 1;
            } else if (table[middle] > c) {
                high = middle - 1;
            } else {
                return values[middle];
            }
        }
        return null;
    }

    /** Starts a fetch if the table is not loaded. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isReady()) {
            listener.onReady(keys.length);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            try {
                build(listener);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Pinyin dictionary build failed", ex);
                listener.onFailed();
            } finally {
                RUNNING.set(false);
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

        load(table);
        RomanizationDict.writeNotice(directory, NOTICE_NAME, noticeText());
        listener.onReady(keys.length);
    }

    /**
     * Reads the readings out of the archive and writes one line per character, holding only
     * the candidates of the character being read: taking the first line instead would pick
     * kHanyuPinlu, which is the poorer source and comes first alphabetically.
     */
    private static void extract(File archive, File table,
            RomanizationDict.Listener listener) throws IOException {
        File built = new File(table.getPath() + ".building");
        long size = archive.length();

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             Writer out = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(built), StandardCharsets.UTF_8), 1 << 16)) {
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

                    int rank = rank(line.substring(firstTab + 1, secondTab));
                    if (rank < 0) continue;

                    char c = codePoint(line.substring(0, firstTab));
                    if (c == 0) continue;
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

    /**
     * The first reading of a field, without its tone marks, its frequency or its location code.
     * Some fields list several, and the frequency oriented ones write one as {@code wo(23213)}.
     */
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

            StringBuilder plain = new StringBuilder(reading.length());
            for (int i = 0; i < reading.length(); i++) {
                char c = reading.charAt(i);
                int tone = TONES.indexOf(c);
                plain.append(tone >= 0 ? UNTONED.charAt(tone) : Character.toLowerCase(c));
            }
            if (!plain.isEmpty()) return plain.toString();
        }
        return null;
    }

    /** Sorted like the database, which is what makes a lookup a binary search. Keys are
     * published last, so a reader that sees one sees its values too. */
    private static void load(File table) throws IOException {
        List<String> lines = Files.readAllLines(table.toPath(), StandardCharsets.UTF_8);
        char[] loadedKeys = new char[lines.size()];
        String[] loadedValues = new String[lines.size()];

        int at = 0;
        for (String line : lines) {
            if (line.length() < 3) continue;
            loadedKeys[at] = line.charAt(0);
            loadedValues[at] = line.substring(2);
            at++;
        }
        values = loadedValues;
        keys = loadedKeys;
    }

    private static String noticeText() {
        return "Unicode Han Database readings\n"
                + "https://www.unicode.org/Public/" + VERSION + "/ucd/Unihan.zip\n\n"
                + "Licence: Unicode License, Version 3\n"
                + "https://www.unicode.org/license.txt\n\n"
                + "Copyright (c) 1991-2026 Unicode, Inc. All rights reserved.\n"
                + "Distributed under the Terms of Use in https://www.unicode.org/copyright.txt\n\n"
                + "What is stored here is the kMandarin reading of each character, taken from\n"
                + "Unihan_Readings.txt in the file above.\n";
    }
}
