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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;

/**
 * The CLDR {@code -Latin} transliteration rules, fetched as one archive. Only literal rules are
 * kept: contextual rules and filters need ICU machinery the lyric reader lacks, and combining
 * marks are dropped (not sung; a marks-only reading loses the letter, like the Hebrew alef).
 * Indic, Thai and Myanmar are excluded, as they reshape letters in context. Downloaded, since
 * the patch merges only XML resources and the rules move with the standard.
 */
public final class CldrTransformsStore {

    /** The CLDR release the rules were taken from. */
    private static final String VERSION = "49.0.0";

    private static final String URL =
            "https://registry.npmjs.org/cldr-transforms/-/cldr-transforms-" + VERSION + ".tgz";
    private static final String ARCHIVE = "cldr-transforms-" + VERSION + ".tgz";
    private static final String NOTICE_NAME = "cldr_transforms.NOTICE.txt";

    /**
     * The CLDR transform per table, named without its extension. The Slavic BGN tables stand
     * apart from the general Cyrillic one, since a Russian lyric reads better with the BGN
     * letters.
     */
    private static final String[][] TRANSLITERATIONS = {
            {"Greek-Latin", "greek"},
            {"Cyrillic-Latin", "cyrillic"},
            {"Russian-Latin-BGN", "russian"},
            {"Ukrainian-Latin-BGN", "ukrainian"},
            {"Belarusian-Latin-BGN", "belarusian"},
            {"Bulgarian-Latin-BGN", "bulgarian"},
            {"Macedonian-Latin-BGN", "macedonian"},
            {"Serbian-Latin-BGN", "serbian"},
            {"Georgian-Latin", "georgian"},
            {"Armenian-Latin-BGN", "armenian"},
            {"Hebrew-Latin", "hebrew"},
            {"Arabic-Latin", "arabic"},
            {"Amharic-Latin-BGN", "amharic"},
            {"Syriac-Latin", "syriac"},
    };
    private static final String TABLE_SUFFIX = "_transforms.txt";

    /**
     * A reading the CLDR standard writes one way and a lyric wants another. The key is the
     * letter or pair in the original script; the value wins over the downloaded rule.
     */
    private static final String[][] OVERRIDES = {
            // Modern Greek is read as it is said: CLDR follows the ancient spelling.
            {"greek", "β", "v", "η", "i", "φ", "f",
                    "ου", "u", "αι", "ai", "ει", "i", "αυ", "av", "ευ", "ev",
                    "ηυ", "iv", "ωυ", "o", "μπ", "mp", "ντ", "nd", "γκ", "gk",
                    "γχ", "ch", "τσ", "ts", "τζ", "tz"},
            // Slavic letters read the commonest way: CLDR follows the transliteration standard.
            {"cyrillic", "ё", "yo", "й", "y", "ї", "yi", "ў", "w", "ќ", "kj", "ѓ", "gj",
                    "ѐ", "yo", "ѝ", "i", "ж", "zh", "х", "kh", "ц", "ts", "ч", "ch",
                    "ш", "sh", "щ", "shch", "ю", "yu", "я", "ya", "є", "ye", "љ", "lj",
                    "њ", "nj", "џ", "dz", "ђ", "dj", "ѕ", "dz"},
            // Georgian differs from CLDR only in the ejective apostrophe, which is not sounded.
            {"georgian", "კ", "k", "პ", "p", "ტ", "t", "ყ", "q", "წ", "ts", "ჭ", "ch",
                    "ჳ", "ui"},
            // Armenian BGN aspirates with an apostrophe, which a lyric does not sound.
            {"armenian", "թ", "t", "չ", "ch", "ռ", "r", "ց", "ts", "փ", "p", "ք", "k",
                    "ւ", "v", "և", "ev"},
    };

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-cldr-transforms");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static final RomanizationDict.Channel CHANNEL = new RomanizationDict.Channel();

    private CldrTransformsStore() {
    }

    /** Loads the tables on first use, since only the settings row downloads them. */
    public static boolean isReady() {
        for (String[] transliteration : TRANSLITERATIONS) {
            if (CldrTransforms.reader(transliteration[1]) == null) return false;
        }
        return true;
    }

    /**
     * Removes the tables and the licence notice. Called when the user turns the
     * transliteration rules off.
     */
    public static void delete() {
        CldrTransforms.clear();
        File directory = RomanizationDict.directory();
        if (directory == null) return;
        for (String[] transliteration : TRANSLITERATIONS) {
            //noinspection ResultOfMethodCallIgnored
            new File(directory, tableName(transliteration[1])).delete();
        }
        //noinspection ResultOfMethodCallIgnored
        new File(directory, NOTICE_NAME).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, ARCHIVE).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(directory, ARCHIVE + ".part").delete();
    }

    /** The licence notice, read back from the cache where the build put it. */
    @Nullable
    public static String notice() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : RomanizationDict.readNotice(directory, NOTICE_NAME);
    }

    /** Starts a fetch if the tables are not loaded. Safe to call whenever the setting is on. */
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
                Logger.printDebug(() -> "CLDR transforms build failed", ex);
                CHANNEL.onFailed();
            } finally {
                RUNNING.set(false);
                CHANNEL.detach();
            }
        });
    }

    private static int entries() {
        int total = 0;
        for (String[] transliteration : TRANSLITERATIONS) {
            AlphabetRomanizer reader = CldrTransforms.reader(transliteration[1]);
            if (reader != null) total += reader.size();
        }
        return total;
    }

    private static void build(RomanizationDict.Listener listener) throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        File archive = new File(directory, ARCHIVE);
        if (!archive.isFile()) {
            RomanizationDict.download(URL, archive, ARCHIVE, listener);
        }
        extract(archive, listener);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        RomanizationDict.writeNotice(directory, NOTICE_NAME, noticeText());
        listener.onReady(entries());
    }

    /**
     * Reads the literal rules out of the archive and writes one table per script, each a line of
     * {@code "source\ttarget"} per rule.
     */
    private static void extract(File archive, RomanizationDict.Listener listener)
            throws IOException {
        File directory = RomanizationDict.directory();
        if (directory == null) throw new IOException("no cache directory");

        int done = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             ByteArrayOutputStream tar = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                tar.write(buffer, 0, read);
            }
            byte[] tarBytes = tar.toByteArray();

            for (String[] transliteration : TRANSLITERATIONS) {
                String entry = "package/transforms/" + transliteration[0] + ".txt";
                byte[] content = TarReader.entry(tarBytes, entry);
                if (content == null) {
                    throw new IOException("no " + entry + " in the CLDR archive");
                }
                writeTable(directory, transliteration[1], content, listener);
                listener.onBuilding(50 + done++ * 50 / TRANSLITERATIONS.length);
            }
        }
        listener.onBuilding(100);
    }

    /** Writes one script's literal rules, with the overrides folded in. */
    private static void writeTable(File directory, String script, byte[] content,
                                   RomanizationDict.Listener listener) throws IOException {
        Map<String, String> rules = parseRules(content, script);

        File built = new File(directory, tableName(script) + ".building");
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(built), StandardCharsets.UTF_8), 1 << 16)) {
            for (Map.Entry<String, String> rule : rules.entrySet()) {
                out.write(rule.getKey());
                out.write('\t');
                out.write(rule.getValue());
                out.write('\n');
            }
        }
        RomanizationDict.commit(built, new File(directory, tableName(script)));
    }

    /**
     * Reads one transform file's literal rules as {@code letter -> reading}: a left side of
     * one or two letters and a right side with no set, context or pipe is all the
     * {@link AlphabetRomanizer} can use.
     */
    private static Map<String, String> parseRules(byte[] content, String script)
            throws IOException {
        Map<String, String> rules = new HashMap<>();
        Map<String, String> variables = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#'
                        || trimmed.startsWith("::")) {
                    continue;
                }

                int variable = variableName(trimmed);
                if (variable > 0) {
                    int end = trimmed.indexOf(';', variable);
                    if (end > 0) {
                        variables.put(trimmed.substring(1, variable).trim(),
                                trimmed.substring(variable + 1, end).trim());
                    }
                    continue;
                }

                int arrow = arrow(trimmed);
                if (arrow < 0) continue;
                String lhs = left(trimmed, arrow);
                if (lhs == null || lhs.length() > 2) continue;
                String rhs = expand(trimmed.substring(arrow + 1), variables);
                if (rhs == null) continue;
                rhs = stripMarks(rhs).trim();
                // A capital rule would never be wanted: the small letter's rule reads both.
                // An empty reading spells the letter away: the alef and the hard and soft
                // signs are read as nothing at all, since no syllable sounds them.
                if (lhs.toLowerCase(Locale.ROOT).equals(lhs)) {
                    rules.put(lhs, rhs);
                }
            }
        }
        applyOverrides(rules, script);
        return rules;
    }

    /** The index of the {@code =} in a {@code $variable = value} line, or -1. */
    private static int variableName(String line) {
        if (line.charAt(0) != '$') return -1;
        for (int i = 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=') return i;
            if (c == ' ') continue;
            if (!Character.isLetterOrDigit(c) && c != '_') return -1;
        }
        return -1;
    }

    /**
     * The index of the arrow in a rule read left to right, or -1. Only the arrows that spell
     * the source script on the left are wanted: a reverse rule (Latin on the left, {@code ←})
     * would otherwise put a reading on a letter the engine never looks up.
     */
    private static int arrow(String line) {
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if ((c == '↔' || c == '→') && i > 0
                    && line.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The left side of a rule: a quoted character, or the bare characters up to the arrow.
     * Null when the side is contextual, writes a set, or is not letters: the engine reads
     * letters, not the punctuation the standard transliterates as a courtesy.
     */
    @Nullable
    private static String left(String line, int arrow) {
        String side = line.substring(0, arrow).trim();
        if (side.startsWith("'")) {
            int close = side.indexOf('\'', 1);
            if (close < 0) return null;
            side = side.substring(1, close);
        } else {
            if (side.indexOf('{') >= 0 || side.indexOf('[') >= 0) return null;
            if (side.length() > 2) return null;
            // A bare escaped character such as \; is punctuation, not a reading.
            if (side.length() == 1 && side.charAt(0) == '\\') return null;
        }
        for (int i = 0; i < side.length(); i++) {
            if (!Character.isLetter(side.charAt(i))) return null;
        }
        return side.isEmpty() ? null : side;
    }

    /**
     * The right side with its {@code $variables} expanded, or null when it holds a set, a
     * context or a pipe.
     */
    @Nullable
    private static String expand(String side, Map<String, String> variables) {
        int end = side.indexOf(';');
        if (end >= 0) side = side.substring(0, end);
        StringBuilder out = new StringBuilder(side.length());
        for (int i = 0; i < side.length(); i++) {
            char c = side.charAt(i);
            if (c == '{' || c == '[' || c == '|' || c == '}') return null;
            if (c == '$') {
                int nameEnd = i + 1;
                while (nameEnd < side.length()
                        && (Character.isLetterOrDigit(side.charAt(nameEnd))
                        || side.charAt(nameEnd) == '_')) {
                    nameEnd++;
                }
                String name = side.substring(i + 1, nameEnd);
                String value = variables.get(name);
                if (value == null) return null;
                String expanded = unescape(value);
                // A variable's value can carry a set or context of its own.
                for (int j = 0; j < expanded.length(); j++) {
                    char ec = expanded.charAt(j);
                    if (ec == '{' || ec == '[' || ec == '|' || ec == '}') return null;
                }
                out.append(expanded);
                i = nameEnd - 1;
                continue;
            }
            if (c == '\\') {
                // A \\uXXXX escape names a character the file cannot hold as itself.
                if (i + 5 < side.length() && side.charAt(i + 1) == 'u') {
                    out.append((char) Integer.parseInt(
                            side.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                }
                // Any other backslash is the CLDR escape for punctuation such as \:
                // which the reading does not want.
                i++;
                continue;
            }
            if (c != '\'') out.append(c);
        }
        return out.toString().trim();
    }

    /** A variable value with its {@code \\uXXXX} escapes decoded, or the value whole. */
    private static String unescape(String value) {
        if (value.indexOf("\\u") < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 5 < value.length() && value.charAt(i + 1) == 'u') {
                out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                i += 5;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Keeps only the letters of a reading. The combining marks and modifier letters CLDR
     * writes to keep the reading reversible are not sounds a lyric sings, and a space is
     * not kept either: the engine spaces the syllables itself.
     */
    private static String stripMarks(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)
                    && Character.getType(c) != Character.MODIFIER_LETTER) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The modern readings that win over the downloaded rules. */
    private static void applyOverrides(Map<String, String> rules, String script) {
        for (String[] overrides : OVERRIDES) {
            if (!script.equals(overrides[0])) continue;
            for (int i = 1; i < overrides.length; i += 2) {
                rules.put(overrides[i], overrides[i + 1]);
            }
        }
    }

    private static String tableName(String script) {
        return script + TABLE_SUFFIX;
    }

    private static String noticeText() {
        return "CLDR transliteration rules\n"
                + URL + "\n\n"
                + "From https://www.unicode.org/cldr/\n\n"
                + "Licence: Unicode Licence V3 (data files and software)\n"
                + "https://www.unicode.org/license.txt\n\n"
                + "The CLDR data files are provided \"as is\", without warranty of any kind,\n"
                + "express or implied, including but not limited to the warranties of\n"
                + "merchantability, fitness for a particular purpose and noninfringement.\n\n"
                + "The rules here are the literal letter-to-letter rules of the listed CLDR\n"
                + "transform files, with the combining marks of the standard dropped and a few\n"
                + "readings a lyric wants replacing the standard's where they differ.\n";
    }

    /**
     * Reads one entry out of a tar archive held in memory, without the tar library Android
     * does not ship. Only the uStar format CLDR's package is written in is read, and only
     * regular files, which is all the archive holds.
     */
    private static final class TarReader {

        private static final int BLOCK = 512;
        private static final int NAME = 0;
        private static final int NAME_LENGTH = 100;
        private static final int SIZE = 124;
        private static final int SIZE_LENGTH = 12;
        private static final int PREFIX = 345;
        private static final int PREFIX_LENGTH = 155;

        private TarReader() {
        }

        @Nullable
        static byte[] entry(byte[] tar, String wanted) throws IOException {
            int offset = 0;
            while (offset + BLOCK <= tar.length) {
                String name = name(tar, offset);
                if (name.isEmpty()) break;
                String full = name(tar, offset, PREFIX, PREFIX_LENGTH);
                if (!full.isEmpty()) {
                    name = full + '/' + name;
                }
                long size = size(tar, offset);
                if (name.equals(wanted)) {
                    if (size > tar.length - offset - BLOCK) {
                        throw new IOException("truncated tar entry " + wanted);
                    }
                    byte[] content = new byte[(int) size];
                    System.arraycopy(tar, offset + BLOCK, content, 0, content.length);
                    return content;
                }
                long total = BLOCK + ((size + BLOCK - 1) / BLOCK) * BLOCK;
                offset += (int) total;
            }
            return null;
        }

        private static String name(byte[] tar, int offset, int field, int length)
                throws IOException {
            int end = offset + field + length;
            if (end > tar.length) throw new IOException("truncated tar header");
            int stop = offset + field;
            while (stop < end && tar[stop] != 0) {
                stop++;
            }
            return new String(tar, offset + field, stop - offset - field,
                    StandardCharsets.UTF_8);
        }

        private static String name(byte[] tar, int offset) throws IOException {
            return name(tar, offset, NAME, NAME_LENGTH);
        }

        private static long size(byte[] tar, int offset) throws IOException {
            String octal = name(tar, offset, SIZE, SIZE_LENGTH);
            long size = 0;
            for (int i = 0; i < octal.length(); i++) {
                char c = octal.charAt(i);
                if (c == ' ' || c == 0) break;
                size = size * 8 + (c - '0');
            }
            return size;
        }
    }
}
