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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * The Cantonese side of the same Unihan download as {@link PinyinDictStore}, which builds this
 * table too since it deletes the archive as soon as it is unpacked.
 */
public final class CantoneseDictStore {

    static final String FIELD = "kCantonese";
    private static final String TABLE_NAME = "cantonese_readings_" + PinyinDictStore.VERSION + ".txt";
    private static final String NOTICE_NAME = "cantonese_readings.NOTICE.txt";

    /** The commit is what names the version: the file is updated in place, and the table
     * has to be rebuilt when it changes. */
    private static final String WORDS_REVISION = "259f0e48bba840c3a2e0d117539e96937f3d89bc";
    private static final String WORDS_URL =
            "https://raw.githubusercontent.com/rime/rime-cantonese/" + WORDS_REVISION
                    + "/jyut6ping3.words.dict.yaml";
    private static final String WORDS_ARCHIVE = "cantonese_words.yaml";
    private static final String WORDS_TABLE_NAME = "cantonese_words_" + WORDS_REVISION + ".bin";
    private static final String WORDS_NOTICE_NAME = "cantonese_words.NOTICE.txt";

    static final byte[] WORDS_MAGIC = {'M', 'C', 'Y', 'W', '0', '0', '0', '1'};

    /** Room to spare, since streaming the source does not say how many entries there will be. */
    static final int WORDS_SLOT_COUNT = 1 << 20;

    private static final int WORDS_PROGRESS_EVERY = 20000;

    private static volatile char[] keys;
    private static volatile String[] values;
    private static volatile WordReadingTable words;

    private CantoneseDictStore() {
    }

    /** Loads the table on first use, since only the settings row downloads it. */
    public static boolean isReady() {
        if (keys != null) return true;
        File table = tableFile();
        if (table == null || !table.isFile()) return false;
        try {
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
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the Cantonese table", ex);
            return false;
        }
        return keys != null;
    }

    @Nullable
    static File tableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, TABLE_NAME);
    }

    @Nullable
    public static String reading(char c) {
        char[] table = keys;
        if (table == null) return null;

        int low = 0;
        int high = table.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            // Unsigned: half of the Han block is negative as a char, and the table is in code point order.
            int here = Character.compare(table[middle], c);
            if (here < 0) {
                low = middle + 1;
            } else if (here > 0) {
                high = middle - 1;
            } else {
                return values[middle];
            }
        }
        return null;
    }

    static void writeNotice(File directory) throws IOException {
        RomanizationDict.writeNotice(directory, NOTICE_NAME,
                PinyinDictStore.noticeTextFor(FIELD));
    }

    /** Null leaves the line to be read a character at a time. */
    @Nullable
    public static WordReadingTable word() {
        WordReadingTable open = words;
        if (open != null) return open;

        File table = wordsTableFile();
        if (table == null) return null;
        open = WordReadingTable.open(table.toPath(), WORDS_MAGIC);
        if (open != null) {
            words = open;
        }
        return open;
    }

    @Nullable
    private static File wordsTableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, WORDS_TABLE_NAME);
    }

    /** The dictionary lists no single characters, which is what the character table is for. */
    static void buildWords(File directory, RomanizationDict.Listener listener) throws IOException {
        File target = new File(directory, WORDS_TABLE_NAME);
        if (target.isFile()) return;

        File archive = new File(directory, WORDS_ARCHIVE);
        if (!archive.isFile()) {
            RomanizationDict.download(WORDS_URL, archive, WORDS_ARCHIVE, listener);
        }

        File built = new File(directory, WORDS_TABLE_NAME + ".building");
        WordReadingTable.Builder builder =
                new WordReadingTable.Builder(built.toPath(), WORDS_MAGIC, WORDS_SLOT_COUNT);
        addWords(archive, builder, listener);
        builder.finish(0);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        RomanizationDict.commit(built, target);
        RomanizationDict.writeNotice(directory, WORDS_NOTICE_NAME, wordsNoticeText());
        words = null;
    }

    /** A word may list the readings it shares with others in brackets; the first is the one
     * wanted. */
    private static void addWords(File archive, WordReadingTable.Builder builder,
            RomanizationDict.Listener listener) throws IOException {
        int seen = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16),
                StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;

                String reading = line.substring(tab + 1);
                int cut = reading.indexOf('[');
                if (cut >= 0) reading = reading.substring(0, cut);
                reading = reading.trim();
                if (reading.isEmpty()) continue;

                builder.put(line.substring(0, tab), reading);
                if (++seen % WORDS_PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WORDS_PROGRESS_EVERY));
                }
            }
            listener.onBuilding(100);
        }
    }

    private static String wordsNoticeText() {
        return "Rime Cantonese word readings\n"
                + WORDS_URL + "\n\n"
                + "From https://github.com/rime/rime-cantonese\n\n"
                + "Licence: Creative Commons Attribution 4.0 International (CC BY 4.0)\n"
                + "https://creativecommons.org/licenses/by/4.0/legalcode\n\n"
                + "Creator: CanCLID, the Cantonese Linguistics Infrastructure Development Group\n"
                + "https://github.com/CanCLID/rime-cantonese-upstream\n\n"
                + "The work is provided as-is and the contributors make no warranties of any kind\n"
                + "concerning it.\n\n"
                + "What is stored here is the Jyutping reading of each word, taken from\n"
                + "jyut6ping3.words.dict.yaml in the file above.\n";
    }

    @Nullable
    public static String wordsNotice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null
                : RomanizationDict.readNotice(directory, WORDS_NOTICE_NAME);
    }

    public static String notice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null
                : RomanizationDict.readNotice(directory, NOTICE_NAME);
    }
}