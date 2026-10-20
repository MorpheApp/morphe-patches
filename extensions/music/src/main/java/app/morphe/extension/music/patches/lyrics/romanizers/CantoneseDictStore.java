/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
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
    private static final String WORDS_NOTICE_NAME = "cantonese_words.NOTICE.txt";
    private static final WordTableFile WORDS = new WordTableFile(
            "cantonese_words_" + WORDS_REVISION + ".bin",
            new byte[] {'M', 'C', 'Y', 'W', '0', '0', '0', '1'}, 1 << 20);

    @Nullable
    private static volatile CharReadingTable characters;

    private CantoneseDictStore() {
    }

    /** Loads the table on first use, since only the settings row downloads it. */
    public static boolean isReady() {
        if (characters != null) return true;
        File table = tableFile();
        if (table == null || !table.isFile()) return false;
        try {
            characters = CharReadingTable.load(table);
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the Cantonese table", ex);
            return false;
        }
        return true;
    }

    @Nullable
    static File tableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, TABLE_NAME);
    }

    @Nullable
    public static String reading(char c) {
        CharReadingTable table = characters;
        return table == null ? null : table.reading(c);
    }

    static void writeNotice(File directory) throws IOException {
        RomanizationDict.writeNotice(directory, NOTICE_NAME,
                PinyinDictStore.noticeTextFor(FIELD));
    }

    /** Null leaves the line to be read a character at a time. */
    @Nullable
    public static WordReadingTable word() {
        return WORDS.open();
    }

    /** The dictionary lists no single characters, which is what the character table is for. */
    static void buildWords(File directory, RomanizationDict.Listener listener) throws IOException {
        WORDS.build(directory, WORDS_URL, WORDS_ARCHIVE, CantoneseDictStore::addWords, listener);
        RomanizationDict.writeNotice(directory, WORDS_NOTICE_NAME, wordsNoticeText());
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
                if (++seen % WordTableFile.PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WordTableFile.PROGRESS_EVERY));
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

    @Nullable
    public static String notice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null
                : RomanizationDict.readNotice(directory, NOTICE_NAME);
    }
}
