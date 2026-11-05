/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class HanRomanizerTest {

    /** Builds a {@link HanRomanizer.Readings} from word and character tables. */
    private static HanRomanizer.Readings readings(Map<String, String> words,
                                                  Map<Character, String> chars) {
        return new HanRomanizer.Readings() {
            @Override
            public String word(String word) {
                return words.get(word);
            }

            @Override
            public String character(char c) {
                return chars.get(c);
            }
        };
    }

    private static Map<String, String> words(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<Character, String> chars(Object... pairs) {
        Map<Character, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Character) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }

    // ── word hits ──

    @Test
    public void romanizeWordHit() {
        HanRomanizer.Readings r = readings(words("你好", "ni hao"), chars());
        assertEquals("ni hao", HanRomanizer.romanize("你好", r));
    }

    @Test
    public void romanizeLongestWordPreference() {
        // Both "你好" and "你" are known, but the longest is tried first.
        HanRomanizer.Readings r = readings(
                words("你好", "combined", "你", "first", "好", "second"), chars());
        assertEquals("combined", HanRomanizer.romanize("你好", r));
    }

    @Test
    public void romanizeMultipleWordHits() {
        HanRomanizer.Readings r = readings(words("你", "ni", "好", "hao"), chars());
        assertEquals("ni hao", HanRomanizer.romanize("你好", r));
    }

    @Test
    public void romanizeWordReturnsEmpty() {
        // A word that returns an empty reading is consumed but produces no output.
        HanRomanizer.Readings r = readings(words("你", ""), chars());
        assertEquals("", HanRomanizer.romanize("你", r));
    }

    // ── per-character fallback ──

    @Test
    public void romanizePerCharacterFallback() {
        // Word not found, but each character has a reading.
        HanRomanizer.Readings r = readings(words(), chars('你', "ni", '好', "hao"));
        assertEquals("ni hao", HanRomanizer.romanize("你好", r));
    }

    @Test
    public void romanizeHanWithoutReadingPassesThrough() {
        // Han with no word and no character reading is passed through.
        HanRomanizer.Readings r = readings(words(), chars());
        assertEquals("你", HanRomanizer.romanize("你", r));
    }

    // ── kana runs ──

    @Test
    public void romanizeKanaRun() {
        // Kana-only input is read by the KanaRomanizer rule.
        HanRomanizer.Readings r = readings(words(), chars());
        assertEquals("konnichiha", HanRomanizer.romanize("こんにちは", r));
    }

    // ── mixed scripts and passthrough ──

    @Test
    public void romanizeMixedScripts() {
        // Han (per char), Latin (passthrough), kana (rule).
        HanRomanizer.Readings r = readings(words(), chars('你', "ni"));
        assertEquals("ni Hello konnichiha", HanRomanizer.romanize("你Helloこんにちは", r));
    }

    @Test
    public void romanizePassthroughNonHan() {
        HanRomanizer.Readings r = readings(words(), chars());
        assertEquals("Hello World", HanRomanizer.romanize("Hello World", r));
    }

    @Test
    public void romanizeEmpty() {
        HanRomanizer.Readings r = readings(words(), chars());
        assertEquals("", HanRomanizer.romanize("", r));
    }

    // ── word followed by character ──

    @Test
    public void romanizeWordThenCharacter() {
        // "你" is a word, "好" falls back to per-character.
        HanRomanizer.Readings r = readings(words("你", "ni"), chars('好', "hao"));
        assertEquals("ni hao", HanRomanizer.romanize("你好", r));
    }
}
