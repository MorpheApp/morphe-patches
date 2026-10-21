/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Cyrillic is written the way it sounds, so this is rules, not a dictionary. A lyric says nothing of
 * the language behind it, so letters the Slavic languages disagree on are read the commonest way: a
 * spelling changes, never a letter count.
 */
public final class CyrillicRomanizer {

    private static final Map<String, String> LETTERS = new HashMap<>();

    static {
        LETTERS.put("а", "a");
        LETTERS.put("б", "b");
        LETTERS.put("в", "v");
        LETTERS.put("г", "g");
        LETTERS.put("д", "d");
        LETTERS.put("е", "e");
        LETTERS.put("ё", "yo");
        LETTERS.put("ж", "zh");
        LETTERS.put("з", "z");
        LETTERS.put("и", "i");
        LETTERS.put("й", "y");
        LETTERS.put("к", "k");
        LETTERS.put("л", "l");
        LETTERS.put("м", "m");
        LETTERS.put("н", "n");
        LETTERS.put("о", "o");
        LETTERS.put("п", "p");
        LETTERS.put("р", "r");
        LETTERS.put("с", "s");
        LETTERS.put("т", "t");
        LETTERS.put("у", "u");
        LETTERS.put("ф", "f");
        LETTERS.put("х", "kh");
        LETTERS.put("ц", "ts");
        LETTERS.put("ч", "ch");
        LETTERS.put("ш", "sh");
        LETTERS.put("щ", "shch");
        // The two signs break a cluster apart and are said on their own nowhere.
        LETTERS.put("ъ", "");
        LETTERS.put("ы", "y");
        LETTERS.put("ь", "");
        LETTERS.put("э", "e");
        LETTERS.put("ю", "yu");
        LETTERS.put("я", "ya");

        LETTERS.put("і", "i");
        LETTERS.put("ї", "yi");
        LETTERS.put("є", "ye");
        LETTERS.put("ґ", "g");
        LETTERS.put("ў", "w");
        LETTERS.put("ј", "j");
        LETTERS.put("љ", "lj");
        LETTERS.put("њ", "nj");
        LETTERS.put("ћ", "c");
        LETTERS.put("ќ", "kj");
        LETTERS.put("џ", "dz");
        LETTERS.put("ђ", "dj");
        LETTERS.put("ѓ", "gj");
        LETTERS.put("ѕ", "dz");
        LETTERS.put("ѐ", "yo");
        LETTERS.put("ѝ", "i");

        // Letters the Slavs write alone and the Turkic tongues borrow, so none comes back untranslated.
        LETTERS.put("ѡ", "o");
        LETTERS.put("ѣ", "ye");
        LETTERS.put("ѥ", "ye");
        LETTERS.put("ѧ", "a");
        LETTERS.put("ѩ", "a");
        LETTERS.put("ѫ", "a");
        LETTERS.put("ѭ", "a");
        LETTERS.put("ѯ", "ae");
        LETTERS.put("ѱ", "ps");
        LETTERS.put("ѳ", "f");
        LETTERS.put("ѵ", "y");
        LETTERS.put("ѷ", "i");
        LETTERS.put("ѹ", "u");
        LETTERS.put("ѻ", "o");
        LETTERS.put("ѽ", "oe");
        LETTERS.put("ѿ", "o");
        LETTERS.put("ғ", "g");
        LETTERS.put("қ", "q");
        LETTERS.put("ң", "ng");
        LETTERS.put("ү", "u");
        LETTERS.put("ұ", "u");
        LETTERS.put("һ", "h");
        LETTERS.put("ҷ", "j");
        LETTERS.put("ә", "a");
    }

    private static final AlphabetRomanizer READER = new AlphabetRomanizer(
            ScriptDetector::isCyrillic, LETTERS, Collections.emptyMap(), false);

    private CyrillicRomanizer() {
    }

    public static String romanize(String line) {
        return READER.romanize(line);
    }
}
