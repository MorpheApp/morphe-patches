/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.KuromojiDictionary.TestLexeme;
import app.morphe.extension.music.patches.lyrics.romanizers.RubyGenerator.RubyStyle;

public class KuromojiRomanizerTest {

    private KuromojiTokenizer tokenizer;

    @Before
    public void setUp() throws IOException {
        List<TestLexeme> lexemes = List.of(
                new TestLexeme("君", (short) 1, (short) 1, (short) 3000, "名詞", "代名詞", "君", "キミ", "キミ"),
                new TestLexeme("の", (short) 2, (short) 2, (short) 2000, "助詞", "連体化", "の", "ノ", "ノ"),
                new TestLexeme("運命", (short) 1, (short) 1, (short) 2800, "名詞", "一般", "運命", "ウンメイ", "ウンメイ"),
                new TestLexeme("ヒト", (short) 1, (short) 1, (short) 3200, "名詞", "一般", "ヒト", "ヒト", "ヒト"),
                new TestLexeme("は", (short) 2, (short) 2, (short) 1800, "助詞", "係助詞", "は", "ハ", "ワ"),
                new TestLexeme("へ", (short) 2, (short) 2, (short) 1800, "助詞", "格助詞", "へ", "ヘ", "エ"),
                new TestLexeme("を", (short) 2, (short) 2, (short) 1800, "助詞", "格助詞", "を", "ヲ", "オ"),
                new TestLexeme("僕", (short) 1, (short) 1, (short) 3000, "名詞", "代名詞", "僕", "ボク", "ボク"),
                new TestLexeme("じゃ", (short) 2, (short) 2, (short) 2100, "助詞", "副助詞", "じゃ", "ジャ", "ジャ"),
                new TestLexeme("ない", (short) 3, (short) 3, (short) 2100, "助動詞", "*", "ない", "ナイ", "ナイ"),
                new TestLexeme("遠回り", (short) 1, (short) 1, (short) 2900, "名詞", "サ変接続", "遠回り", "トオマワリ", "トーマワリ"),
                new TestLexeme("し", (short) 3, (short) 3, (short) 2200, "動詞", "自立", "する", "シ", "シ"),
                new TestLexeme("て", (short) 2, (short) 2, (short) 1900, "助詞", "接続助詞", "て", "テ", "テ"),
                new TestLexeme("言っ", (short) 3, (short) 3, (short) 2400, "動詞", "自立", "言う", "イッ", "イッ"),
                new TestLexeme("た", (short) 3, (short) 3, (short) 1900, "助動詞", "*", "た", "タ", "タ"),
                new TestLexeme("こんにちは", (short) 1, (short) 1, (short) 2500, "感動詞", "*", "こんにちは", "コンニチハ", "コンニチワ"),
                new TestLexeme("明日", (short) 1, (short) 1, (short) 2700, "名詞", "副詞可能", "明日", "アシタ", "アシタ"),
                new TestLexeme("世界", (short) 1, (short) 1, (short) 2700, "名詞", "一般", "世界", "セカイ", "セカイ"),
                new TestLexeme("やって", (short) 1, (short) 1, (short) 2200, "動詞", "自立", "やる", "ヤッテ", "ヤッテ"),
                new TestLexeme("来", (short) 3, (short) 3, (short) 2200, "動詞", "非自立", "来る", "キ", "キ"),
                new TestLexeme("まし", (short) 3, (short) 3, (short) 1900, "助動詞", "*", "ます", "マシ", "マシ"),
                new TestLexeme("よ", (short) 2, (short) 2, (short) 1800, "助詞", "終助詞", "よ", "ヨ", "ヨ"),
                new TestLexeme("真白", (short) 1, (short) 1, (short) 2800, "名詞", "形容動詞語幹", "真白", "マッシロ", "マッシロ"),
                new TestLexeme("な", (short) 3, (short) 3, (short) 1800, "助動詞", "*", "だ", "ナ", "ナ"),
                // Tokens for Japanese reading, particle, and attachment rule coverage
                new TestLexeme("限り", (short) 1, (short) 1, (short) 2500, "名詞", "一般", "限り", "カギリ", "カギリ"),
                new TestLexeme("の", (short) 2, (short) 2, (short) 1500, "助詞", "連体化", "の", "ノ", "ノ"),
                new TestLexeme("無い", (short) 1, (short) 1, (short) 2500, "形容詞", "自立", "無い", "ナイ", "ナイ"),
                new TestLexeme("時", (short) 1, (short) 1, (short) 2500, "名詞", "一般", "時", "トキ", "トキ"),
                new TestLexeme("を", (short) 2, (short) 2, (short) 1500, "助詞", "格助詞", "を", "ヲ", "ヲ"),
                new TestLexeme("越え", (short) 1, (short) 1, (short) 2500, "動詞", "自立", "越える", "コエ", "コエ"),
                new TestLexeme("て", (short) 2, (short) 2, (short) 1500, "助詞", "接続助詞", "て", "テ", "テ"),
                new TestLexeme("今", (short) 1, (short) 1, (short) 2500, "名詞", "副詞可能", "今", "イマ", "イマ"),
                new TestLexeme("その", (short) 1, (short) 1, (short) 2500, "連体詞", "*", "その", "ソノ", "ソノ"),
                new TestLexeme("手", (short) 1, (short) 1, (short) 2500, "名詞", "一般", "手", "テ", "テ"),
                new TestLexeme("に", (short) 2, (short) 2, (short) 1500, "助詞", "格助詞", "に", "ニ", "ニ"),
                new TestLexeme("触れ", (short) 1, (short) 1, (short) 2500, "動詞", "自立", "触れる", "フレ", "フレ"),
                new TestLexeme("させ", (short) 2, (short) 2, (short) 1800, "動詞", "接尾", "させる", "サセ", "サセ"),
                new TestLexeme("黒い", (short) 1, (short) 1, (short) 2500, "形容詞", "自立", "黒い", "クロイ", "クロイ"),
                new TestLexeme("感情", (short) 1, (short) 1, (short) 2500, "名詞", "一般", "感情", "カンジョウ", "カンジョー"),
                new TestLexeme("も", (short) 2, (short) 2, (short) 1500, "助詞", "係助詞", "も", "モ", "モ"),
                new TestLexeme("君", (short) 1, (short) 1, (short) 2500, "名詞", "代名詞", "君", "キミ", "キミ"),
                new TestLexeme("へ", (short) 2, (short) 2, (short) 1500, "助詞", "格助詞", "へ", "ヘ", "エ"),
                new TestLexeme("繋ぐ", (short) 1, (short) 1, (short) 2500, "動詞", "自立", "繋ぐ", "ツナグ", "ツナグ"),
                new TestLexeme("だから", (short) 1, (short) 1, (short) 2500, "接続詞", "*", "だから", "ダカラ", "ダカラ"),
                new TestLexeme("どうか", (short) 1, (short) 1, (short) 2500, "副詞", "一般", "どうか", "ドウカ", "ドーカ"),
                new TestLexeme("すべて", (short) 1, (short) 1, (short) 2500, "名詞", "副詞可能", "すべて", "スベテ", "スベテ"),
                new TestLexeme("愛し", (short) 1, (short) 1, (short) 2500, "動詞", "自立", "愛する", "アイシ", "アイシ"),
                new TestLexeme("は", (short) 2, (short) 2, (short) 1500, "助詞", "係助詞", "は", "ハ", "ワ"),
                new TestLexeme("たら", (short) 2, (short) 2, (short) 1500, "助詞", "接続助詞", "たら", "タラ", "タラ"),
                new TestLexeme("いく", (short) 2, (short) 2, (short) 1800, "動詞", "非自立", "いく", "イク", "イク"),
                new TestLexeme("何", (short) 1, (short) 1, (short) 2500, "名詞", "代名詞", "何", "ナニ", "ナニ"),
                new TestLexeme("か", (short) 2, (short) 2, (short) 1500, "助詞", "副助詞", "か", "カ", "カ")
        );
        KuromojiDictionary dict = KuromojiDictionary.buildSyntheticForTest(lexemes);
        assertNotNull(dict);
        tokenizer = new KuromojiTokenizer(dict);
        KuromojiDictStore.setTestTokenizer(tokenizer);
    }

    @After
    public void tearDown() {
        KuromojiDictStore.setTestTokenizer(null);
    }

    @Test
    public void romanizesParticlesAndContextualKanjiReadings() {
        String romaji = KuromojiRomanizer.romanizeLine("君の運命のヒトは僕じゃない", tokenizer);
        assertEquals("kimi no unmei no hito wa boku janai", romaji);
    }

    @Test
    public void romanizesDirectionalParticleCrossTokenSokuonAndGreeting() {
        assertEquals("ashita e itta", KuromojiRomanizer.romanizeLine("明日へ言った", tokenizer));
        assertEquals("konnichiwa sekai", KuromojiRomanizer.romanizeLine("こんにちは世界", tokenizer));
    }

    @Test
    public void alignsPerCharacterTimedWordsInsideCompoundTokensWithoutDrift() {
        // Unison/Apple per-character timed words inside "遠回りして" -> ["遠", "回", "り", "し", "て"]
        List<Word> words = List.of(
                new Word(0, 200, "遠"),
                new Word(200, 400, "回"),
                new Word(400, 550, "り"),
                new Word(550, 700, "し"),
                new Word(700, 900, "て")
        );
        List<Word> aligned = KuromojiRomanizer.alignLineWords("遠回りして", words, tokenizer);
        assertNotNull(aligned);
        assertEquals(5, aligned.size());
        assertEquals("too", aligned.get(0).romaji().trim());
        assertEquals("mawa", aligned.get(1).romaji().trim());
        assertEquals("ri", aligned.get(2).romaji().trim());
        assertEquals("shi", aligned.get(3).romaji().trim());
        assertEquals("te", aligned.get(4).romaji().trim());
    }

    @Test
    public void enrichesLyricsWithContextualFuriganaRuby() {
        LyricsLine line1 = new LyricsLine(0, 3000, "遠回りして明日へ", List.of());
        Lyrics input = new Lyrics(
                List.of(line1), "Test", true, null, null, null, null, null, null, null);

        Lyrics enriched = KuromojiRomanizer.enrichLyricsRuby(input, tokenizer, RubyStyle.HIRAGANA);
        List<Word> rubyWords = enriched.lines().get(0).words();
        assertTrue(!rubyWords.isEmpty());
        // "遠回り" splits kanji stem "遠回" (ruby "とおまわ") from okurigana "り" (no redundant ruby).
        assertEquals("遠回", rubyWords.get(0).text());
        assertEquals("とおまわ", rubyWords.get(0).ruby());
        assertEquals("り", rubyWords.get(1).text());

        boolean foundAshita = false;
        for (Word w : rubyWords) {
            if ("明日".equals(w.text())) {
                assertEquals("あした", w.ruby());
                foundAshita = true;
            }
        }
        assertTrue(foundAshita);
    }

    @Test
    public void japaneseMoraSplitterSplitsSyllablesPreservingCasing() {
        assertEquals(List.of("To", "o", "ma", "wa", "ri"),
                JapaneseMoraSplitter.splitMorasRaw("Toomawari"));
        assertEquals(List.of("u", "n", "me", "i"),
                JapaneseMoraSplitter.splitMorasRaw("unmei"));
        assertEquals(List.of("shi", "te"),
                JapaneseMoraSplitter.splitMorasRaw("shite"));
    }

    @Test
    public void rubySlicingPreservesPerWordRomajiForMixedKanjiKanaWords() {
        List<Word> words = List.of(
                new Word(0, 1000, "やって来ました", "yatte kimashita", true, null),
                new Word(1000, 1400, "よ", "yo", true, null),
                new Word(1400, 2200, "真白な", "masshiro na", false, null)
        );
        LyricsLine line = new LyricsLine(0, 2200, "やって来ましたよ真白な", words);
        Lyrics enriched = KuromojiRomanizer.enrichLyricsRuby(
                new Lyrics(List.of(line), "Test", true, null, null, null, null, null, null, null),
                tokenizer, RubyStyle.HIRAGANA);
        List<Word> rubyWords = enriched.lines().get(0).words();
        assertTrue(rubyWords.size() >= 3);
        for (Word w : rubyWords) {
            assertNotNull("Ruby-sliced word '" + w.text() + "' must keep its romaji", w.romaji());
            assertTrue("Ruby-sliced word '" + w.text() + "' must have non-empty romaji",
                    !w.romaji().trim().isEmpty());
        }
    }

    @Test
    public void packTargetMapRejectsOversizedCountsAndMultiplicationOverflow() {
        byte[] hugeKeyCount = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Integer.MAX_VALUE)
                .putInt(0)
                .array();
        boolean rejectedKeyCount = false;
        try {
            KuromojiDictionary.packTargetMap(hugeKeyCount);
        } catch (IOException expected) {
            rejectedKeyCount = true;
        }
        assertTrue(rejectedKeyCount);

        byte[] overflowValSize = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1)
                .putInt(0)
                .putInt(0x40000001)
                .array();
        boolean rejectedOverflow = false;
        try {
            KuromojiDictionary.packTargetMap(overflowValSize);
        } catch (IOException expected) {
            rejectedOverflow = true;
        }
        assertTrue(rejectedOverflow);
    }

    @Test
    public void writePackedFromFilesMatchesInMemoryPack() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("kuromoji-pack-test").toFile();
        try {
            byte[][] raw = new byte[12][];
            File[] rawFiles = new File[12];
            for (int i = 0; i < 12; i++) {
                if (i == 4) {
                    raw[i] = ByteBuffer.allocate(16)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(1)
                            .putInt(42)
                            .putInt(1)
                            .putInt(7)
                            .array();
                } else {
                    raw[i] = new byte[]{(byte) (i + 1), (byte) (i + 2), (byte) (i + 3)};
                }
                rawFiles[i] = new File(dir, "sec_" + i + ".raw");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(rawFiles[i])) {
                    fos.write(raw[i]);
                }
            }
            File memOut = new File(dir, "mem.bin");
            File diskOut = new File(dir, "disk.bin");
            int memEntries = KuromojiDictionary.writePackedFile(
                    memOut,
                    raw[0], raw[1], raw[2], raw[3], raw[4], raw[5],
                    raw[6], raw[7], raw[8], raw[9], raw[10], raw[11]);
            int diskEntries = KuromojiDictionary.writePackedFromFiles(diskOut, rawFiles);
            assertEquals(memEntries, diskEntries);
            assertTrue(java.util.Arrays.equals(
                    java.nio.file.Files.readAllBytes(memOut.toPath()),
                    java.nio.file.Files.readAllBytes(diskOut.toPath())));

            // SHA-256 verification accepts matching digest and rejects tampered data
            File gzFile = new File(dir, "sample.dat.gz");
            byte[] payload = "kuromoji-sha-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(
                    new java.io.FileOutputStream(gzFile))) {
                gz.write(payload);
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            File okRaw = new File(dir, "ok.raw");
            KuromojiDictStore.decompressToFile(gzFile, okRaw, hex.toString());
            assertTrue(java.util.Arrays.equals(payload, java.nio.file.Files.readAllBytes(okRaw.toPath())));

            File badRaw = new File(dir, "bad.raw");
            boolean mismatchThrown = false;
            try {
                KuromojiDictStore.decompressToFile(gzFile, badRaw, "0".repeat(64));
            } catch (IOException expected) {
                mismatchThrown = true;
            }
            assertTrue(mismatchThrown);
            assertTrue(!badRaw.exists());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        } finally {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) {
                    //noinspection ResultOfMethodCallIgnored
                    c.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    @Test
    public void romanizeLinesAppliesHepburnParticlesAndAttachmentRules() {
        assertEquals("kagiri no nai toki o koete ima",
                KuromojiRomanizer.romanizeLine("限りの無い時を越えて今", tokenizer));

        List<Word> l1Words = KuromojiRomanizer.alignLineWords("限りの無い時を越えて今",
                java.util.Arrays.asList(
                        new Word(0, 100, "限", null, false),
                        new Word(100, 200, "りの", null, false),
                        new Word(200, 300, "無い", null, false),
                        new Word(300, 400, "時を", null, false),
                        new Word(400, 500, "越", null, false),
                        new Word(500, 600, "えて", null, false),
                        new Word(600, 700, "今", null, false)
                ), tokenizer);
        assertEquals(7, l1Words.size());
        assertEquals("kagi", l1Words.get(0).romaji().trim());
        assertEquals("ri no", l1Words.get(1).romaji().trim());
        assertEquals("nai", l1Words.get(2).romaji().trim());
        assertEquals("toki o", l1Words.get(3).romaji().trim());
        assertEquals("ko", l1Words.get(4).romaji().trim());
        assertEquals("ete", l1Words.get(5).romaji().trim());
        assertEquals("ima", l1Words.get(6).romaji().trim());

        assertEquals("sono te ni furesasete",
                KuromojiRomanizer.romanizeLine("その手に触れさせて", tokenizer));
        assertEquals("kuroi kanjou mo kimi e tsunagu",
                KuromojiRomanizer.romanizeLine("黒い感情も君へ繋ぐ", tokenizer));
        assertEquals("dakara douka subete aishite",
                KuromojiRomanizer.romanizeLine("だからどうかすべて 愛して", tokenizer));

        // Attachment rules: 助詞+助詞 (には), 接続助詞 (たら), 補助動詞 (ていく), 何+か
        assertEquals("kimi niwa", KuromojiRomanizer.romanizeLine("君には", tokenizer));
        assertEquals("koetara", KuromojiRomanizer.romanizeLine("越えたら", tokenizer));
        assertEquals("koeteiku", KuromojiRomanizer.romanizeLine("越えていく", tokenizer));
        assertEquals("nanika", KuromojiRomanizer.romanizeLine("何か", tokenizer));
    }

    @Test
    public void syllableReadingsForSlotsMapsOkuriganaAndPreservesParticles() {
        String line = "限りの無い時を越えて今";
        int[] starts = {0, 1, 3, 5, 7, 8, 10};
        int[] ends = {1, 3, 5, 7, 8, 10, 11};
        String[] readings = KuromojiRomanizer.syllableReadingsForSlots(line, starts, ends, tokenizer);
        assertEquals(7, readings.length);
        assertEquals("kagi", readings[0].trim());
        assertEquals("ri no", readings[1].trim());
        assertEquals("nai", readings[2].trim());
        assertEquals("toki o", readings[3].trim());
        assertEquals("ko", readings[4].trim());
        assertEquals("ete", readings[5].trim());
        assertEquals("ima", readings[6].trim());
    }
}
