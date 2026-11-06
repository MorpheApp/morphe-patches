/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

/**
 * Tests for the private rebuild methods of {@link LyricsFileSaver} via reflection.
 * The public {@code save} method requires a Context and MediaStore, which are not
 * available in unit tests.
 */
public class LyricsFileSaverTest {

    private TrackInfo track;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        track = LyricsFixtures.track("Test Song", "Test Artist");
    }

    private String callPrivateMethod(String name, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Method method = LyricsFileSaver.class.getDeclaredMethod(name, paramTypes);
        method.setAccessible(true);
        Object result = method.invoke(null, args);
        return (String) result;
    }

    @Test
    public void rebuildPlainTextReturnsText() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        String result = callPrivateMethod("rebuildPlainText",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        assertTrue(result.contains("first line"));
        assertTrue(result.contains("second line"));
    }

    @Test
    public void rebuildLrcReturnsValidLrc() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, "first line"),
                new LyricsLine(10000, "second line"));
        String result = callPrivateMethod("rebuildLrc",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        // LRC format: [mm:ss.xx]text
        assertTrue(result.contains("[00:00.00]"));
        assertTrue(result.contains("[00:10.00]"));
        assertTrue(result.contains("first line"));
        assertTrue(result.contains("second line"));
    }

    @Test
    public void rebuildKrcReturnsValidKrc() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, 1000, "first line", List.of(),
                        null, false, false, null),
                new LyricsLine(1000, 2000, "second line", List.of(),
                        null, false, false, null));
        String result = callPrivateMethod("rebuildKrc",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        // KRC format: [startMs,durationMs]text
        assertTrue(result.contains("[0,1000]"));
        assertTrue(result.contains("[1000,1000]"));
        assertTrue(result.contains("first line"));
        assertTrue(result.contains("second line"));
    }

    @Test
    public void rebuildLyricifyLinesReturnsValidFormat() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        String result = callPrivateMethod("rebuildLyricifyLines",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        assertTrue(result.contains("[type:LyricifyLines]"));
        assertTrue(result.contains("first line"));
    }

    @Test
    public void rebuildLyricifySyllableReturnsValidFormat() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, "hello world"));
        String result = callPrivateMethod("rebuildLyricifySyllable",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        assertTrue(result.contains("[from:AppleSyllable]"));
    }

    @Test
    public void rebuildDzrJsonReturnsValidJson() throws Exception {
        List<LyricsLine> lines = List.of(
                new LyricsLine(0, "first line"),
                new LyricsLine(1000, "second line"));
        String result = callPrivateMethod("rebuildDzrJson",
                new Class<?>[]{List.class}, lines);
        assertNotNull(result);
        // JSON should have curly braces
        assertTrue(result.contains("{"));
        assertTrue(result.contains("}"));
    }

    @Test
    public void fileNameRawTtmlSavedAsProviderName() throws Exception {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "Hello"));
        Lyrics lyrics = new Lyrics(lines, "YouTube Music", true,
                null, null, null, null, "<tt/>", "ttml", null, null, null);
        String result = callPrivateMethod("fileName",
                new Class<?>[]{TrackInfo.class, Lyrics.class, String.class, boolean.class},
                track, lyrics, "ttml", false);
        assertTrue("Raw TTML should use provider name",
                result.contains("[YouTube Music]"));
    }

    @Test
    public void fileNameGeneratedTtmlSavedAsMorphe() throws Exception {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "Hello"));
        Lyrics lyrics = new Lyrics(lines, "YouTube Music", true,
                null, null, null, null, "<tt/>", "ttml", null, null, null);
        String result = callPrivateMethod("fileName",
                new Class<?>[]{TrackInfo.class, Lyrics.class, String.class, boolean.class},
                track, lyrics, "ttml", true);
        assertTrue("Generated TTML should use Morphe",
                result.contains("[Morphe]"));
    }

    @Test
    public void fileNameSourceFormatAlwaysUsesProviderName() throws Exception {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "Hello"));
        Lyrics lyrics = new Lyrics(lines, "YouTube Music", true,
                null, null, null, null, null, "ttml", null, null, null);
        String result = callPrivateMethod("fileName",
                new Class<?>[]{TrackInfo.class, Lyrics.class, String.class, boolean.class},
                track, lyrics, "ttml", false);
        assertTrue("Source format should use provider name even with null rawFormat",
                result.contains("[YouTube Music]"));
    }
}
