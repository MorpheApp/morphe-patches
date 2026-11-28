/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;

public class QQProviderTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void parseDecodedPayloadsNormalizesMoraSplitJapaneseRomaji() {
        String lyricPayload = "<QrcInfos><LyricInfo>"
                + "<Lyric_1 LyricType=\"1\" LyricContent=\""
                + "[offset:50]\n"
                + "[by:Satoshi]\n"
                + "[1000,2400]まどろむ(1000,900) キミ(1900,600) に(2500,300) そそぐ(2800,600)"
                + "\"/></LyricInfo></QrcInfos>";
        String romaPayload = "[1000,2400]ma (1000,200)do (1200,200)ro (1400,200)mu (1600,300)"
                + "ki (1900,300)mi (2200,300)ni (2500,300)so (2800,200)so (3000,200)gu(3200,200)";

        Lyrics lyrics = QQProvider.parseDecodedPayloads(
                lyricPayload, romaPayload, null, "https://y.qq.com/n/ryqq/songDetail/1", false);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertEquals(1050L, lyrics.lines().get(0).startTimeMs());
        assertNotNull(lyrics.credits());
        assertTrue(lyrics.credits().contains("by:Satoshi"));

        assertNotNull(lyrics.romanization());
        assertEquals(1, lyrics.romanization().size());
        String normalizedRoma = lyrics.romanization().get(0).text();
        assertEquals("madoromu kimi ni sosogu", normalizedRoma);
    }

    @Test
    public void parseDecodedPayloadsAlignsConsecutiveKanjiViaQrcTimestampsWithoutKuromoji() {
        String lyricPayload = "<QrcInfos><LyricInfo>"
                + "<Lyric_1 LyricType=\"1\" LyricContent=\""
                + "[offset:1500]\n"
                + "[1000,1800]風(1000,500) の(1500,300) 声(1800,500) 光(2300,500)\n"
                + "[4000,1400]涙(4000,700)を(4700,300)拭(5000,250)く(5250,150)"
                + "\"/></LyricInfo></QrcInfos>";
        String romaPayload = "[1000,1800]ka (1000,250)ze (1250,250)no (1500,300)ko (1800,250)e (2050,250)hi (2300,200)ka (2500,150)ri(2650,150)\n"
                + "[4000,1400]na (4000,200)mi (4200,200)da (4400,300)wo (4700,300)fu (5000,250)ku(5250,150)";

        Lyrics lyrics = QQProvider.parseDecodedPayloads(
                lyricPayload, romaPayload, null, "https://y.qq.com/n/ryqq/songDetail/3", false);
        assertNotNull(lyrics);
        assertEquals(2, lyrics.lines().size());
        assertEquals(2500L, lyrics.lines().get(0).startTimeMs());
        assertNotNull(lyrics.romanization());
        assertEquals(2, lyrics.romanization().size());
        assertEquals("kaze no koe hikari", lyrics.romanization().get(0).text());
        assertEquals("namidawo fuku", lyrics.romanization().get(1).text());
    }

    @Test
    public void parseDecodedPayloadsPreservesNonJapaneseRomajiAndChineseTranslation() {
        String lyricPayload = "[2000,1500]夜(2000,500)曲(2500,500)\n[4000,100]// comment";
        String romaPayload = "[2000,1500]ye (2000,500)qu(2500,500)\n[4000,100]//";
        String transPayload = "[2000,1500]Nocturne\n[4000,100]//";

        Lyrics lyrics = QQProvider.parseDecodedPayloads(
                lyricPayload, romaPayload, transPayload, "https://y.qq.com/n/ryqq/songDetail/2", true);
        assertNotNull(lyrics);
        assertEquals(1, lyrics.lines().size());
        assertNotNull(lyrics.romanization());
        assertEquals("ye qu", lyrics.romanization().get(0).text());
        assertNotNull(lyrics.translations());
        assertEquals("Nocturne", lyrics.translations().get("zh-Hans").get(0).text());
    }
}
