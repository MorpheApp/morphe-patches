/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

/**
 * The word readings for the languages a Rime dictionary knows, taken from Rime word lists,
 * which know a word the {@link UniDictStore} character tables do not. The character reading of a
 * Han character comes from {@link UniDictStore}; this store only adds what a word is read as.
 *
 * <p>One table per language, named by the BCP-47 primary language tag. The language the user
 * asked to romanize picks the table, so a new language is one row in {@link #BUILTIN} or one line
 * of {@link Settings#LYRICS_RIME_CUSTOM_DICTS}.
 *
 * <p>Only Cantonese ships. Every other word list is added by the user, who supplies its URL
 * and therefore judges its licence and freshness; the patch never bundles a list whose terms
 * it cannot vouch for. Downloaded rather than shipped, because the patch can only merge XML
 * resources and because a word list goes stale as soon as the source moves on.
 */
public final class RimeDictStore {

    /** The character table a word table falls back to for the characters a word does not cover. */
    public static final int CHAR_SOURCE_AUTO = 0;
    public static final int CHAR_SOURCE_CANTONESE = 1;
    public static final int CHAR_SOURCE_PINYIN = 2;
    public static final int CHAR_SOURCE_NONE = 3;
    /** Progress on the character tables is the Local provider's row to report, not this one's. */
    public static final RomanizationDict.Listener SILENT = new RomanizationDict.Listener() {
        @Override
        public void onDownload(String fileName, int percent) {
        }

        @Override
        public void onBuilding(int percent) {
        }

        @Override
        public void onReady(int entries) {
        }

        @Override
        public void onFailed() {
        }
    };
    /** The Rime dictionaries read by this store, each the word list of one language. */
    private static final List<RimeLang> BUILTIN = List.of(
            // The commit is what names the version: the file is updated in place, and the table
            // has to be rebuilt when it changes.
            new RimeLang("yue",
                    "https://raw.githubusercontent.com/rime/rime-cantonese/"
                            + "259f0e48bba840c3a2e0d117539e96937f3d89bc/jyut6ping3.words.dict.yaml",
                    "cantonese_words.yaml",
                    "cantonese_words_259f0e48bba840c3a2e0d117539e96937f3d89bc.bin",
                    "cantonese_words.NOTICE.txt",
                    "粤语",
                    new byte[]{'M', 'C', 'Y', 'W', '0', '0', '0', '1'},
                    1 << 20, CHAR_SOURCE_CANTONESE, false));
    /** The word table files, one per language, opened on first use. */
    private static final Map<String, WordTableFile> TABLES = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-rime-dict");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** Set while a saved choice is fetching its tables one after another. */
    private static final AtomicBoolean BATCHING = new AtomicBoolean();
    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();
    /** A word table of a few hundred thousand lines is already far past a useful size. */
    private static final int MAX_LINES = 4_000_000;
    /**
     * The characters read from one source. {@link #MAX_LINES} alone still lets a tiny archive
     * that unpacks to the same four million long lines cost minutes of reading, so the total
     * read is bounded as well. The largest real list seen is about twenty million characters.
     */
    private static final long MAX_SOURCE_CHARS = 64L << 20;
    /**
     * What a user-supplied word list may download as. The largest Rime list seen is twenty
     * megabytes of text; anything far past that is not a word list, and a zip could otherwise
     * be made to fill the disk.
     */
    private static final long MAX_SOURCE_BYTES = 64L << 20;
    /** Forwards progress only: a per-table "ready" would end the batch early. */
    private static final RomanizationDict.Listener BATCH_PROGRESS = new RomanizationDict.Listener() {
        @Override
        public void onDownload(String fileName, int percent) {
            CHANNEL.onDownload(fileName, percent);
        }

        @Override
        public void onBuilding(int percent) {
            CHANNEL.onBuilding(percent);
        }

        @Override
        public void onReady(int entries) {
        }

        @Override
        public void onFailed() {
        }
    };
    /** The size the built-in table is known to download at, shown before it is fetched. */
    private static final String FALLBACK_BUILTIN_MB = "3 MB";
    /**
     * A single line longer than this is not a word list, and reading it would cost the heap
     * far more than the entry could ever be worth.
     */
    private static final int MAX_LINE_LENGTH = 1 << 16;
    /**
     * The languages whose Han characters are read by a romanizer this build already has. These
     * are reserved even when the language list cannot be read: a word table under one of them
     * would shadow a reading that works.
     */
    private static final Set<String> HAN_READING_LANGUAGES = Set.of("ja", "zh", "yue");
    private static volatile Snapshot snapshot;
    /** The tags the romanization language list offers, read once. */
    private static volatile Set<String> shippedLanguages;

    private RimeDictStore() {
    }

    /** Whether the language the user asked for has a word table here. */
    public static boolean isActive() {
        return lang() != null;
    }

    /**
     * Loads on first use; the character tables must be there too, or per-character readings
     * a word table does not cover would be missing.
     */
    public static boolean isReady() {
        RimeLang lang = lang();
        return lang != null && characterTablesReady(lang) && table(lang).open() != null;
    }

    /** Whether the chosen language needs a character table at all, and has it. */
    private static boolean characterTablesReady(RimeLang lang) {
        return lang.charSource == CHAR_SOURCE_NONE || UniDictStore.isReady();
    }

    /**
     * Removes one language's word table, for when the user takes that list away. Must run while
     * the settings still name the language, since the file names come from that row.
     */
    public static void deleteTable(String code) {
        RimeLang lang = langs().get(code);
        // A reserved row, or a built-in table that is switched off, has left the table map but its
        // files are still named after it.
        if (lang == null) lang = storedLang(code);
        if (lang != null) {
            removeFiles(lang);
        }
    }

    /** The row of a language that parsed but is not a reading source right now. */
    @Nullable
    private static RimeLang storedLang(String code) {
        for (RimeLang lang : loaded().stored) {
            if (lang.code.equals(code)) return lang;
        }
        for (RimeLang lang : BUILTIN) {
            if (lang.code.equals(code)) return lang;
        }
        return null;
    }

    private static void removeFiles(RimeLang lang) {
        synchronized (TABLES) {
            WordTableFile table = TABLES.remove(lang.code);
            if (table != null) {
                table.clear();
            }
        }
        File directory = RomanizationDict.directory();
        if (directory == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(directory, lang.noticeName).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, lang.tableName).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, lang.archive).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, lang.archive + ".part").delete();
    }

    /** Forgets the cached tables and the parsed settings, so the next read sees a change. */
    public static void reset() {
        snapshot = null;
        synchronized (TABLES) {
            for (WordTableFile table : TABLES.values()) {
                table.clear();
            }
            TABLES.clear();
        }
    }

    /** The reading of a character, delegated to the shared character tables. */
    @Nullable
    public static String reading(char c) {
        RimeLang lang = lang();
        if (lang == null) return null;
        switch (lang.charSource) {
            case CHAR_SOURCE_CANTONESE:
                return UniDictStore.cantoneseReading(c);
            case CHAR_SOURCE_PINYIN:
                return UniDictStore.pinyinReading(c);
            default:
                return null;
        }
    }

    /** Null leaves the line to be read a character at a time. */
    @Nullable
    public static WordReadingTable word() {
        RimeLang lang = lang();
        return lang == null ? null : table(lang).open();
    }

    /** Starts a fetch if the table is not loaded. Safe to call whenever the setting is on. */
    public static void ensure(RomanizationDict.Listener listener) {
        if (isReady()) {
            listener.onReady(entries());
            return;
        }
        // A saved choice is already fetching every ticked table, this one among them. Starting a
        // second build beside it would queue the same work twice behind the single worker.
        if (BATCHING.get()) {
            CHANNEL.attach(listener, false);
            return;
        }
        CHANNEL.attach(listener, RUNNING.get());
        if (!RUNNING.compareAndSet(false, true)) return;

        // The language is read once, here: a build that finished after the user switched
        // language must not report the table it built as the new language's.
        final RimeLang lang = lang();
        if (lang == null) {
            // Nothing to fetch for this language; say so rather than leaving the listener
            // waiting, which would hold the settings row's progress at "downloading".
            RUNNING.set(false);
            CHANNEL.onReady(0);
            CHANNEL.detach();
            return;
        }
        WORKER.execute(() -> {
            try {
                build(lang, CHANNEL);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Rime dictionary build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    /**
     * Fetches every ticked table, one after another, so a Save downloads what the boxes promised
     * rather than only the table of the chosen language. Progress goes through the same listener
     * a single fetch uses, but only the end of the last table reports ready.
     *
     * <p>A fetch already on its way is not cancelled: the worker is single threaded, so this
     * batch waits behind it and reattaches its listener when its turn comes.
     */
    public static void ensureSelected(RomanizationDict.Listener listener) {
        if (anySelectedNeedsCharacterTable()
                && !UniDictStore.isRunning() && !UniDictStore.isReady()) {
            UniDictStore.ensure(SILENT);
        }
        final List<RimeLang> targets = new ArrayList<>(langs().values());
        if (targets.isEmpty()) {
            listener.onReady(0);
            return;
        }
        BATCHING.set(true);
        CHANNEL.attach(listener, false);
        WORKER.execute(() -> {
            boolean failed = false;
            int entries = 0;
            try {
                // A one-off fetch may have run in between and let go of the channel.
                CHANNEL.attach(listener, false);
                for (RimeLang target : targets) {
                    try {
                        if (table(target).open() == null) {
                            build(target, BATCH_PROGRESS);
                        }
                        WordReadingTable open = table(target).open();
                        if (open != null) entries = open.size();
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Rime dictionary build failed: " + target.code, ex);
                        failed = true;
                    }
                }
                if (failed) {
                    CHANNEL.onFailed();
                } else {
                    CHANNEL.onReady(entries);
                }
            } finally {
                BATCHING.set(false);
                CHANNEL.detach();
            }
        });
    }

    /** The name shown under a reading, for the dictionary that supplied it. */
    @Nullable
    public static String dictionaryName() {
        RimeLang lang = lang();
        return lang == null ? null : lang.dictionaryName;
    }

    /**
     * Every notice, one per added list, so the about screen credits everything downloaded
     * even when the current language is another.
     */
    @Nullable
    public static String allNotices() {
        File directory = RomanizationDict.directory();
        if (directory == null) return null;
        StringBuilder text = new StringBuilder();
        for (RimeLang lang : langs().values()) {
            String part = RomanizationDict.readNotice(directory, lang.noticeName);
            if (part == null) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(part);
        }
        return text.length() > 0 ? text.toString() : null;
    }

    /** The languages the user added, for the settings row, in the order they were added. */
    public static List<String> customLanguages() {
        List<String> codes = new ArrayList<>();
        for (RimeLang lang : loaded().stored) {
            codes.add(lang.code);
        }
        return codes;
    }

    /** The built-in tables whose switch is on, read from the comma separated setting. */
    private static Set<String> parseBuiltin(@Nullable String stored) {
        Set<String> on = new HashSet<>();
        if (stored == null || stored.isBlank()) return on;
        for (String token : stored.split(",")) {
            final String code = token.trim().toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) on.add(code);
        }
        return on;
    }

    /** Records whether a built-in table is ticked, so it is fetched only when asked for. */
    public static void setBuiltinSelected(String code, boolean selected) {
        final String stored = Settings.LYRICS_RIME_BUILTIN_SELECTED.get();
        final StringBuilder kept = new StringBuilder();
        if (stored != null && !stored.isBlank()) {
            for (String token : stored.split(",")) {
                final String value = token.trim();
                if (value.isEmpty() || value.equalsIgnoreCase(code)) continue;
                if (kept.length() > 0) kept.append(',');
                kept.append(value);
            }
        }
        if (selected) {
            if (kept.length() > 0) kept.append(',');
            kept.append(code);
        }
        Settings.LYRICS_RIME_BUILTIN_SELECTED.save(kept.toString());
        reset();
    }

    /** Whether the switch of a built-in table is on, for the dialog to draw its box. */
    public static boolean isBuiltinRowSelected(String code) {
        return loaded().builtinOn.contains(code.toLowerCase(Locale.ROOT));
    }

    /**
     * The stored line of a user-added list, so a caller can rewrite the setting without
     * rebuilding the line from fields it would have to know the order of.
     */
    @Nullable
    public static String customRow(String code) {
        final String stored = Settings.LYRICS_RIME_CUSTOM_DICTS.get();
        if (stored == null || stored.isBlank()) return null;
        for (String line : stored.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 2);
            if (parts.length > 0 && parts[0].trim().equalsIgnoreCase(code)) {
                return line;
            }
        }
        return null;
    }

    /**
     * The tables that ship already added, in the order they are listed. Cantonese is the example:
     * it is what a user-added list is meant to look like, and it is already in the list rather
     * than something that has to be added.
     */
    public static List<String> builtinLanguages() {
        List<String> codes = new ArrayList<>();
        for (RimeLang lang : BUILTIN) {
            codes.add(lang.code);
        }
        return codes;
    }

    /** The name of a built-in table, or null when the language has none. */
    @Nullable
    public static String builtinLabel(String code) {
        for (RimeLang lang : BUILTIN) {
            if (lang.code.equals(code)) return lang.dictionaryName;
        }
        return null;
    }

    /** The name the user gave a language, or null when it is not one of theirs. */
    @Nullable
    public static String customLabel(String code) {
        for (RimeLang lang : loaded().stored) {
            if (lang.code.equals(code)) return lang.dictionaryName;
        }
        return null;
    }

    /**
     * Whether a user-supplied row names a language this build already reads, which is a row
     * that is kept but never used for reading.
     */
    public static boolean isReserved(String code) {
        return isShippedLanguage(code);
    }

    /**
     * The size and source shown under a row: the table's size on disk and the repository it came
     * from, as "~3 MB, rime/rime-cantonese". The size of a built-in table is known before it is
     * fetched, so it reads the same either way; an added list has no size until it is on disk,
     * and shows only its repository until then.
     *
     * @return the summary, or null when the row names no table.
     */
    @Nullable
    public static String summaryFor(String code) {
        final RimeLang lang = storedLang(code);
        if (lang == null) return null;

        final String repo = repositoryOf(lang.url);
        final long bytes = downloadedBytes(lang);
        final String megabytes = bytes > 0
                ? Math.max(1, Math.round(bytes / (1024.0 * 1024.0))) + " MB"
                : (!lang.custom ? FALLBACK_BUILTIN_MB : null);
        if (megabytes == null) {
            return repo;
        }
        return repo == null ? "~" + megabytes : "~" + megabytes + ", " + repo;
    }

    /** The table file's size, or 0 when it has not been built. */
    private static long downloadedBytes(RimeLang lang) {
        final File directory = RomanizationDict.directory();
        if (directory == null) return 0;
        final File table = new File(directory, lang.tableName);
        return table.isFile() ? table.length() : 0;
    }

    /** The "owner/repo" a git URL names: the first two non-empty path segments. */
    @Nullable
    private static String repositoryOf(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) return null;
            // The owner/repo is the first two non-empty path segments; the file and its
            // branch live after them (e.g., raw.githubusercontent.com/owner/repo/branch/file).
            String[] parts = path.split("/");
            String owner = null, repo = null;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (owner == null) owner = part;
                else if (repo == null) {
                    repo = part;
                    break;
                }
            }
            if (owner == null || repo == null) return null;
            return owner + "/" + repo;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Whether the table of the language in use needs the character tables beside it. */
    public static boolean needsCharacterTable() {
        RimeLang lang = lang();
        return lang != null && lang.charSource != CHAR_SOURCE_NONE;
    }

    /** Whether any ticked table reads characters through the character tables. */
    private static boolean anySelectedNeedsCharacterTable() {
        for (RimeLang lang : langs().values()) {
            if (lang.charSource != CHAR_SOURCE_NONE) return true;
        }
        return false;
    }

    /**
     * Starts the character tables and then the word table, for the Rime Wordlist provider. The word
     * table alone is not enough to read a line: the characters no word covers come from
     * {@link UniDictStore}, so both are fetched before the table reports ready.
     */
    public static void ensureForProvider(RomanizationDict.Listener listener) {
        if (needsCharacterTable() && !UniDictStore.isRunning() && !UniDictStore.isReady()) {
            UniDictStore.ensure(SILENT);
        }
        ensure(listener);
    }

    /**
     * Whether the language already has a table, the built-in one included. A built-in table that is
     * switched off still counts: a second list under the same code could never be read.
     */
    public static boolean hasLanguage(String code) {
        if (langs().containsKey(code)) return true;
        for (RimeLang lang : BUILTIN) {
            if (lang.code.equals(code)) return true;
        }
        return false;
    }

    /**
     * Whether the language is read through a table right now: a built-in table whose switch is on,
     * or one of the user's lists. A built-in table that is switched off is not a reading source.
     */
    public static boolean isSelected(String code) {
        return langs().containsKey(code);
    }

    /** Whether a language may get a user-supplied list: one this build does not already read. */
    public static boolean canAddLanguage(String code) {
        return !hasLanguage(code) && !isShippedLanguage(code);
    }

    private static int entries() {
        WordReadingTable words = word();
        return words == null ? 0 : words.size();
    }

    private static void build(RimeLang lang, RomanizationDict.Listener listener)
            throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        // A language taken away while the fetch ran has nothing to write and nothing to say.
        if (!langs().containsKey(lang.code)) return;

        WordTableFile table = table(lang);
        // A word list larger than the table it is read into cannot add anything the table
        // would keep, so a list past this size is not fetched at all.
        table.build(directory, lang.url, lang.archive, RimeDictStore::addWords, listener,
                lang.custom ? MAX_SOURCE_BYTES : Long.MAX_VALUE);
        RomanizationDict.writeNotice(directory, lang.noticeName, noticeText(lang));
        WordReadingTable built = table.open();
        listener.onReady(built == null ? 0 : built.size());
    }

    /**
     * Reads a word list into the table. A list may arrive as the YAML itself or as a zip holding
     * it; the zip is read from its entry rather than unpacked, so nothing it names can be written
     * outside the dictionary directory.
     *
     * <p>A word may list the readings it shares with others in brackets; the first is the one
     * wanted. The table itself keeps the first reading of a surface form, which is the one a list
     * sorted by frequency puts first.
     */
    private static void addWords(File archive, WordReadingTable.Builder builder,
                                 RomanizationDict.Listener listener) throws IOException {
        // The address decides the name, but the bytes decide how to read it: an archive served
        // from an address without ".zip" at the end is still an archive, and reading it as text
        // would fill the table with the zip header instead of failing.
        if (!isArchive(archive)) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(archive), 1 << 16)) {
                readWords(in, builder, listener);
            }
            return;
        }

        // A Rime archive holds other YAML beside the list, so the list proper is looked for
        // first and any YAML is only a fallback.
        if (readArchive(archive, builder, listener, true)) return;
        if (readArchive(archive, builder, listener, false)) return;
        throw new IOException("no .dict.yaml inside " + archive.getName());
    }

    /** Whether the file starts with the bytes a zip starts with. */
    private static boolean isArchive(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }

    /** Reads the first word list in the archive whose name passes {@link #isWordList}. */
    private static boolean readArchive(File archive, WordReadingTable.Builder builder,
                                       RomanizationDict.Listener listener, boolean dictOnly) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(archive), 1 << 16))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                final String name = entry.getName();
                if (dictOnly ? !isDictFile(name) : !isWordList(name)) continue;
                readWords(zip, builder, listener);
                return true;
            }
        }
        return false;
    }

    /** A file named as a Rime dictionary, rather than some other YAML beside it. */
    private static boolean isDictFile(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dict.yaml") || lower.endsWith(".dict.yml");
    }

    private static boolean isWordList(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return isDictFile(lower) || lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static void readWords(InputStream in, WordReadingTable.Builder builder,
                                  RomanizationDict.Listener listener) throws IOException {
        int seen = 0;
        try (LineReader reader = new LineReader(in)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (++seen > MAX_LINES || reader.consumed() > MAX_SOURCE_CHARS) break;

                int tab = line.indexOf('\t');
                if (tab <= 0) continue;

                String reading = line.substring(tab + 1);
                // A list may add a frequency or code column after the reading; the reading is
                // the first column only, so a word is never read as "tam\t50%".
                int next = reading.indexOf('\t');
                if (next >= 0) reading = reading.substring(0, next);
                int cut = reading.indexOf('[');
                if (cut >= 0) reading = reading.substring(0, cut);
                reading = reading.trim();
                if (reading.isEmpty()) continue;

                builder.put(line.substring(0, tab), reading);
                if (seen % WordTableFile.PROGRESS_EVERY == 0) {
                    listener.onBuilding(Math.min(99, seen / WordTableFile.PROGRESS_EVERY));
                }
            }
        }
        listener.onBuilding(100);
    }

    private static String noticeText(RimeLang lang) {
        if (!lang.custom) {
            return "Rime " + lang.code + " word readings\n"
                    + lang.url + "\n\n"
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
        return "Rime " + lang.code + " word readings\n"
                + lang.url + "\n\n"
                + "Added by the user, who chose this word list and supplies it from that address.\n"
                + "Its licence, provenance and correctness are the user's to confirm; nothing\n"
                + "about it is bundled with or endorsed by this patch.\n\n"
                + "What is stored here is the reading of each word, in the form the list gives it.\n";
    }

    /** The table of the language, or a new one named by the row. */
    private static WordTableFile table(RimeLang lang) {
        synchronized (TABLES) {
            WordTableFile table = TABLES.get(lang.code);
            if (table == null) {
                table = new WordTableFile(lang.tableName, lang.magic, lang.slotCount);
                TABLES.put(lang.code, table);
            }
            return table;
        }
    }

    /** The language the user asked to romanize, as a BCP-47 primary subtag. */
    @Nullable
    private static RimeLang lang() {
        final String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        if (language == null) return null;
        final String subtag = language.toLowerCase(Locale.ROOT).split("-", 2)[0];
        return langs().get(subtag);
    }

    /** The built-in tables plus the user's, parsed once per settings value. */
    private static Map<String, RimeLang> langs() {
        return loaded().langs;
    }

    private static Snapshot loaded() {
        String stored = Settings.LYRICS_RIME_CUSTOM_DICTS.get();
        if (stored == null) stored = "";
        String builtinRaw = Settings.LYRICS_RIME_BUILTIN_SELECTED.get();
        if (builtinRaw == null) builtinRaw = "";

        Snapshot held = snapshot;
        if (held != null && held.raw.equals(stored) && held.builtinRaw.equals(builtinRaw)) {
            return held;
        }

        final List<RimeLang> parsed = parseCustom(stored);
        final Set<String> builtinOn = parseBuiltin(builtinRaw);
        Map<String, RimeLang> map = new LinkedHashMap<>();
        for (RimeLang lang : BUILTIN) {
            // A built-in table is read only while its switch is on, so an upgrade spends no data
            // on a table nobody asked for.
            if (builtinOn.contains(lang.code)) {
                map.put(lang.code, lang);
            }
        }
        for (RimeLang lang : parsed) {
            // The built-in table of a language is never replaced by a downloaded one, and a
            // language the build already reads keeps its reading: a word list under "ja" would
            // otherwise take Japanese away from the dictionary that ships with it. The row is
            // still kept in the setting, so it can be seen and removed rather than stranded.
            if (isShippedLanguage(lang.code)) continue;
            map.putIfAbsent(lang.code, lang);
        }
        map = java.util.Collections.unmodifiableMap(map);
        held = new Snapshot(stored, builtinRaw, java.util.Collections.unmodifiableList(parsed),
                java.util.Collections.unmodifiableSet(builtinOn), map);
        snapshot = held;
        return held;
    }

    /**
     * One line per list, as {@code subtag|label|url|charSource}. A malformed line is skipped
     * rather than taking the good ones down with it.
     */
    private static List<RimeLang> parseCustom(@Nullable String stored) {
        List<RimeLang> parsed = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return parsed;

        for (String line : stored.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) continue;

            final String code = parts[0].trim().toLowerCase(Locale.ROOT);
            final String label = parts[1].trim();
            final String url = parts[2].trim();
            if (!isValidCode(code) || label.isEmpty() || !isSecure(url)) continue;

            // Two lines for one language would show the same row twice, and only the first could
            // ever supply a reading. The first line wins, as it does in the table map.
            boolean seen = false;
            for (RimeLang already : parsed) {
                if (already.code.equals(code)) {
                    seen = true;
                    break;
                }
            }
            if (seen) continue;

            parsed.add(new RimeLang(code, url, archiveName(code, url),
                    tableName(code, url), noticeName(code), label, magicFor(code),
                    1 << 20, parseCharSource(parts[3]), true));
        }
        return parsed;
    }

    /** Whether the build already reads this language through its own list. */
    public static boolean isShippedLanguage(String code) {
        Set<String> cached = shippedLanguages;
        if (cached == null) {
            cached = readShippedLanguages();
            shippedLanguages = cached;
        }
        return cached.contains(code);
    }

    private static Set<String> readShippedLanguages() {
        try {
            final Set<String> read = new HashSet<>();
            for (String value : ResourceUtils.getStringArray(
                    "morphe_music_lyrics_romanize_language_entry_values")) {
                if (value != null && !value.isBlank()) {
                    read.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!read.isEmpty()) return read;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the romanization language list", ex);
        }
        // The list could not be read, so the languages that read Han are reserved by hand
        // rather than leaving them open to a list that would take their reading away.
        return HAN_READING_LANGUAGES;
    }

    /**
     * A BCP-47 primary tag this store can match: two or three lowercase letters. A region
     * suffix is not accepted, because the language is chosen by primary subtag and a row keyed
     * by a longer tag would never be selected. The tag ends up in a file name, so nothing else
     * may get through.
     */
    private static boolean isValidCode(String code) {
        if (code.length() < 2 || code.length() > 3) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    /** Only a plain HTTPS address is fetched: the app carries no authority to vouch for more. */
    private static boolean isSecure(String url) {
        return url.startsWith("https://") && url.length() > "https://".length();
    }

    /**
     * The stored names carry the address, so pointing a language at another list rebuilds it
     * instead of reusing the old table. The suffix follows the address, which is only a hint:
     * the reader tells a zip from a plain word list by its bytes.
     */
    private static String archiveName(String code, String url) {
        final String suffix = isZip(url) ? ".zip" : ".yaml";
        return "rime_" + code + "_" + Integer.toHexString(url.hashCode()) + suffix;
    }

    /**
     * The address's own name, ignoring a query string, as the only thing that says a zip was
     * asked for.
     */
    private static boolean isZip(String url) {
        final String path = url.split("[?#]", 2)[0];
        return path.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static String tableName(String code, String url) {
        return "rime_" + code + "_" + Integer.toHexString(url.hashCode()) + ".bin";
    }

    private static String noticeName(String code) {
        return "rime_" + code + ".NOTICE.txt";
    }

    /**
     * Eight bytes naming the table's writer; a table built for another language or address
     * would not match and is rebuilt.
     */
    private static byte[] magicFor(String code) {
        byte[] magic = {'M', 'R', 'I', 'M', 'E', 0, 0, 0};
        int hash = code.hashCode();
        magic[5] = (byte) hash;
        magic[6] = (byte) (hash >>> 8);
        magic[7] = (byte) (hash >>> 16);
        return magic;
    }

    private static int parseCharSource(String value) {
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CANTONESE":
                return CHAR_SOURCE_CANTONESE;
            case "PINYIN":
                return CHAR_SOURCE_PINYIN;
            case "NONE":
                return CHAR_SOURCE_NONE;
            default:
                return CHAR_SOURCE_AUTO;
        }
    }

    /** One language's word table: where it is fetched from and what it is stored as. */
    private record RimeLang(String code, String url, String archive, String tableName,
                            String noticeName, String dictionaryName, byte[] magic, int slotCount,
                            int charSource, boolean custom) {
    }

    /**
     * The tables the user added, with the settings text they were parsed from. Kept as one
     * snapshot because {@link #lang()} runs for every character read, which is far too often to
     * parse the setting each time.
     *
     * @param raw        The custom-list setting this was parsed from.
     * @param builtinRaw The built-in switch setting this was parsed from, so ticking one is seen at once.
     * @param stored     Every row that parsed, including the ones whose language is reserved.
     * @param builtinOn  The built-in tables whose switch is on.
     * @param langs      The tables a language may actually be read with.
     */
    private record Snapshot(String raw, String builtinRaw, List<RimeLang> stored,
                            Set<String> builtinOn, Map<String, RimeLang> langs) {
    }

    /**
     * A line reader whose memory is bounded by the line length, not by the stream: {@link
     * BufferedReader#readLine} would grow a line as long as a decompression bomb says.
     */
    private static final class LineReader implements Closeable {
        private final Reader reader;
        private final char[] buffer = new char[1 << 13];
        private int at;
        private int end;
        /** Characters read from the stream, which is what bounds the work a source can ask for. */
        private long consumed;
        /** Set after a '\r', so the '\n' of a CRLF pair does not end a second, empty line. */
        private boolean afterCarriageReturn;

        LineReader(InputStream in) {
            reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        }

        /** Characters read so far, whatever became of them. */
        long consumed() {
            return consumed;
        }

        /**
         * Null at the end of the stream. A line longer than {@link #MAX_LINE_LENGTH} is
         * consumed and comes back empty, which the caller skips like any other blank line.
         */
        @Nullable
        String readLine() throws IOException {
            StringBuilder line = new StringBuilder(64);
            boolean tooLong = false;

            while (true) {
                if (at >= end) {
                    end = reader.read(buffer);
                    at = 0;
                    if (end <= 0) {
                        return tooLong ? null : line.toString();
                    }
                }
                final char c = buffer[at++];
                consumed++;

                if (afterCarriageReturn) {
                    afterCarriageReturn = false;
                    if (c == '\n') continue;
                }
                if (c == '\r') {
                    afterCarriageReturn = true;
                    return tooLong ? "" : line.toString();
                }
                if (c == '\n') {
                    return tooLong ? "" : line.toString();
                }
                if (tooLong) continue;
                if (line.length() >= MAX_LINE_LENGTH) {
                    line.setLength(0);
                    tooLong = true;
                    continue;
                }
                line.append(c);
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

}
