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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.morphe.extension.shared.Logger;

/**
 * The Japanese reading dictionary, rebuilt whenever the upstream dictionary moves on.
 *
 * <p>The readings are the SudachiDict sources published by Works Applications under the Apache
 * License 2.0. Downloaded rather than shipped, because the patch can only merge XML resources
 * and because a dictionary that rarely changes should not be paid for by every install.
 */
public final class JapaneseDictStore {

    /** Upstream publishes a release every few months. */
    public static final String VERSION = "20261015";

    private static final String BASE_URL =
            "https://sudachi.s3.ap-northeast-1.amazonaws.com/sudachidict-raw/v1/";

    private static final String[] SOURCES = {"small_lex.zip", "core_lex.zip", "notcore_lex.zip"};

    private static final String TABLE_NAME = "japanese_readings_" + VERSION + ".bin";
    private static final String NOTICE_NAME = "japanese_readings.NOTICE.txt";

    /** How many entries go in before the row is worth moving. */
    private static final int PROGRESS_EVERY = 50000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-japanese-dict");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static volatile JapaneseReadingTable table;

    private JapaneseDictStore() {
    }

    public static boolean isReady() {
        File file = tableFile();
        return file != null && file.isFile()
                && file.length() > JapaneseReadingTable.HEADER_BYTES;
    }

    /** Starts a build if the table is missing. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isReady()) {
            JapaneseReadingTable opened = table();
            if (opened != null) {
                listener.onReady(opened.size());
                return;
            }
        }
        if (!RUNNING.compareAndSet(false, true)) return;

        WORKER.execute(() -> {
            try {
                build(listener);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Japanese dictionary build failed", ex);
                listener.onFailed();
            } finally {
                RUNNING.set(false);
            }
        });
    }

    /** Null when the table has not been built. */
    @Nullable
    public static JapaneseReadingTable table() {
        JapaneseReadingTable open = table;
        if (open != null) return open;

        File file = tableFile();
        if (file == null) return null;
        open = JapaneseReadingTable.open(file.toPath());
        if (open != null) {
            table = open;
        }
        return open;
    }

    @Nullable
    public static String notice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : RomanizationDict.readNotice(directory, NOTICE_NAME);
    }

    @Nullable
    private static File tableFile() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, TABLE_NAME);
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        File built = new File(directory, TABLE_NAME + ".building");
        //noinspection ResultOfMethodCallIgnored
        built.delete();

        JapaneseReadingTable.Builder builder = new JapaneseReadingTable.Builder(built.toPath());
        for (String source : SOURCES) {
            File archive = new File(directory, source);
            if (!archive.isFile()) {
                RomanizationDict.download(BASE_URL + VERSION + "/" + source, archive,
                        displayName(source), listener);
            }
            addSource(builder, archive, listener);
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
        builder.finish(Integer.parseInt(VERSION));

        RomanizationDict.commit(built, tableFile());
        RomanizationDict.writeNotice(directory, NOTICE_NAME, noticeText());
        listener.onReady(builder.size());
    }

    private static void addSource(JapaneseReadingTable.Builder builder, File archive,
            RomanizationDict.Listener listener) throws IOException {
        int seen = 0;

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(archive), 1 << 16))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zip, StandardCharsets.UTF_8), 1 << 16);
                String[] header = split(reader.readLine());
                int surface = indexOf(header, "IndexForm");
                int reading = indexOf(header, "ReadingForm");
                if (surface < 0 || reading < 0) {
                    throw new IOException("unexpected columns in " + entry.getName());
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    String[] fields = split(line);
                    int last = Math.max(surface, reading);
                    if (fields.length <= last) continue;

                    String word = fields[surface];
                    String kana = fields[reading];
                    if (word.isEmpty() || kana.isEmpty()) continue;

                    // A kana word is in the table for its boundary only: the rule already
                    // knows how to read it.
                    builder.put(word, containsHan(word) ? kana : "");
                    if (++seen % PROGRESS_EVERY == 0) {
                        listener.onBuilding(Math.min(99, seen / PROGRESS_EVERY));
                    }
                }
                listener.onBuilding(100);
            }
        }
    }

    private static int indexOf(String[] header, String name) {
        if (header == null) return -1;
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i])) return i;
        }
        return -1;
    }

    /** A field may be quoted and hold commas, so a plain split would shift every
     * column after it. */
    private static String[] split(String line) {
        if (line == null) return new String[0];

        String[] fields = new String[Math.max(8, Math.min(line.length(), 64))];
        int count = 0;
        int start = 0;
        boolean quoted = false;

        for (int i = 0; i <= line.length(); i++) {
            char c = i < line.length() ? line.charAt(i) : ',';
            if (quoted) {
                if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                if (count < fields.length) {
                    fields[count] = unquote(line.substring(start, i));
                }
                count++;
                start = i + 1;
            }
        }

        String[] result = new String[count];
        System.arraycopy(fields, 0, result, 0, Math.min(count, fields.length));
        return result;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) return true;
        }
        return false;
    }

    private static String displayName(String fileName) {
        if (fileName.endsWith(".zip")) fileName = fileName.substring(0, fileName.length() - 4);
        return fileName.replace("_lex", "").replace('_', ' ');
    }

    /** Downloaded rather than shipped, so the licence travels with the data into the
     * cache and is shown from the About row. */
    private static String noticeText() {
        return "SudachiDict reading data\n"
                + "https://github.com/WorksApplications/SudachiDict\n\n"
                + "Licence: Apache License, Version 2.0\n"
                + "https://www.apache.org/licenses/LICENSE-2.0\n\n"
                + "Source files: " + BASE_URL + VERSION + "/\n\n"
                + "As its authors state, this project includes UniDic and a part of Neologd,\n"
                + "and is distributed under the Apache License, Version 2.0. UniDic is\n"
                + "additionally available under the BSD 3-Clause licence.\n\n"
                + "What is stored here is a table of surface forms and their katakana\n"
                + "readings, derived from the files above.\n";
    }
}
