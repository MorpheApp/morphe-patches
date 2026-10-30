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
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * The CLDR transliteration rules, one {@link AlphabetRomanizer} per script: a script written as
 * it sounds is read letter by letter, everything else whole. Adding a table touches
 * {@link CldrTransformsStore#TRANSLITERATIONS}, {@link #STRIP_ACCENTS} and {@link #scriptPredicate};
 * the Slavic BGN tables share the Cyrillic blocks and predicate.
 */
final class CldrTransforms {

    /** The reader of each script the store has written a table for. */
    private static final Map<String, AlphabetRomanizer> READERS = new HashMap<>();

    private static final Map<String, Boolean> STRIP_ACCENTS = new HashMap<>();

    static {
        STRIP_ACCENTS.put("greek", Boolean.TRUE);
        STRIP_ACCENTS.put("cyrillic", Boolean.FALSE);
        STRIP_ACCENTS.put("russian", Boolean.FALSE);
        STRIP_ACCENTS.put("ukrainian", Boolean.FALSE);
        STRIP_ACCENTS.put("belarusian", Boolean.FALSE);
        STRIP_ACCENTS.put("bulgarian", Boolean.FALSE);
        STRIP_ACCENTS.put("macedonian", Boolean.FALSE);
        STRIP_ACCENTS.put("serbian", Boolean.FALSE);
        STRIP_ACCENTS.put("georgian", Boolean.FALSE);
        STRIP_ACCENTS.put("armenian", Boolean.FALSE);
        STRIP_ACCENTS.put("hebrew", Boolean.FALSE);
        STRIP_ACCENTS.put("arabic", Boolean.FALSE);
        STRIP_ACCENTS.put("amharic", Boolean.FALSE);
        STRIP_ACCENTS.put("syriac", Boolean.FALSE);
    }

    private CldrTransforms() {
    }

    /** The reader of a script, or null while its table is still being fetched. */
    @Nullable
    static AlphabetRomanizer reader(String script) {
        AlphabetRomanizer loaded = READERS.get(script);
        if (loaded != null) return loaded;
        File file = tableFile(script);
        if (file == null || !file.isFile()) return null;
        try {
            loaded = readTable(file, script);
            READERS.put(script, loaded);
            return loaded;
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the CLDR table " + script, ex);
            return null;
        }
    }

    /** Forgets the readers so a deleted table is fetched again. */
    static void clear() {
        READERS.clear();
    }

    @Nullable
    private static File tableFile(String script) {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, script + "_transforms.txt");
    }

    private static AlphabetRomanizer readTable(File file, String script) throws IOException {
        Map<String, String> letters = new HashMap<>();
        Map<String, String> digraphs = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(file), 1 << 16),
                StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String source = line.substring(0, tab);
                String reading = line.substring(tab + 1).trim();
                // An empty reading spells the letter away: the alef and the hard and soft
                // signs are read as nothing at all.
                if (source.length() == 1) {
                    letters.put(source, reading);
                } else if (source.length() == 2) {
                    digraphs.put(source, reading);
                }
            }
        }
        return new AlphabetRomanizer(scriptPredicate(script), letters, digraphs,
                stripAccents(script));
    }

    private static AlphabetRomanizer.Script scriptPredicate(String script) {
        switch (script) {
            case "greek":
                return ScriptDetector::isGreek;
            case "cyrillic":
            case "russian":
            case "ukrainian":
            case "belarusian":
            case "bulgarian":
            case "macedonian":
            case "serbian":
                return ScriptDetector::isCyrillic;
            case "georgian":
                return ScriptDetector::isGeorgian;
            case "armenian":
                return ScriptDetector::isArmenian;
            case "hebrew":
                return ScriptDetector::isHebrew;
            case "arabic":
                return ScriptDetector::isArabic;
            case "amharic":
                return ScriptDetector::isEthiopic;
            case "syriac":
                return ScriptDetector::isSyriac;
            default:
                return c -> false;
        }
    }

    private static boolean stripAccents(String script) {
        Boolean strip = STRIP_ACCENTS.get(script);
        return strip != null && strip;
    }
}
