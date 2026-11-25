/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/** Word boundaries by script: word table, tokens, or spaces. */
public final class CjkWordGroups {

    private CjkWordGroups() {
    }

    /** {@return null when the script has no word boundaries} */
    @Nullable
    public static int[] forLine(@Nullable String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        final ScriptDetector.Script script = ScriptDetector.detect(line);
        if (script == ScriptDetector.Script.HAN) {
            return PinyinRomanizer.wordGroups(line);
        }
        if (script == ScriptDetector.Script.JAPANESE) {
            final KuromojiTokenizer tokenizer = KuromojiDictStore.tokenizer();
            return tokenizer == null ? null : KuromojiRomanizer.wordGroups(line, tokenizer);
        }
        if (script == ScriptDetector.Script.KOREAN) {
            return HangulRomanizer.wordGroups(line);
        }
        return null;
    }
}
