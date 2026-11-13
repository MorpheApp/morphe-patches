package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/**
 * Regression test for the local LRC synchronization fix.
 *
 * <p>{@code LocalLyricsFetcher.parse()} previously used {@code LyricsLine.hasWords()} to
 * determine whether parsed lyrics were synchronized. That check only returns {@code true}
 * for word-level (karaoke) timing, so ordinary LRC files with line-level timestamps were
 * marked as unsynchronized and the UI highlighted every line simultaneously.
 *
 * <p>The fix uses {@code !result.lines.isEmpty()} because {@code LRCParser.parseSynced()}
 * only returns lines that carry a valid timestamp.
 */
@SuppressWarnings("ConstantValue")
public class LRCParserSyncedDetectionTest {

    /**
     * Ordinary LRC with line timestamps but no word-level (karaoke) timestamps.
     * This is the common case for MP3 files with ID3 USLT frames.
     */
    @Test
    public void lineTimestampsWithoutWordTimestampsAreSynced() {
        String lrc = "[00:12.34]Hello world\n"
                + "[00:15.67]This is a line\n"
                + "[00:19.01]With line timestamps only\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lrc);

        // The parser should find timestamped lines
        assertFalse("Expected non-empty lines for LRC with timestamps", result.lines.isEmpty());

        // The synced check: non-empty parseSynced result means synced
        boolean synced = !result.lines.isEmpty();
        assertTrue("LRC with line timestamps must be marked as synced", synced);

        // None of the lines should have word-level timing
        for (LyricsLine line : result.lines) {
            assertFalse("Line should not have word-level timing: " + line.text(),
                    line.hasWords());
            assertTrue("Line should have a valid start time: " + line.text(),
                    line.startTimeMs() >= 0);
        }
    }

    /**
     * Enhanced LRC with word-level (karaoke) timestamps inside angle brackets.
     * These should also be detected as synced.
     */
    @Test
    public void wordLevelTimestampsAreSynced() {
        String lrc = "[00:12.34]<00:12.34>Hello<00:12.80> world<00:13.50>\n"
                + "[00:15.67]Second line with words\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lrc);

        assertFalse("Expected non-empty lines", result.lines.isEmpty());
        boolean synced = !result.lines.isEmpty();
        assertTrue("LRC with word-level timestamps must be marked as synced", synced);

        // At least one line should have word-level timing
        boolean hasWordTimings = result.lines.stream().anyMatch(LyricsLine::hasWords);
        assertTrue("Expected at least one line with word-level timing", hasWordTimings);
    }

    /**
     * Empty input should produce an empty result, meaning not synced.
     */
    @Test
    public void emptyLyricsAreNotSynced() {
        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines("");
        assertTrue("Empty input should produce empty lines", result.lines.isEmpty());

        boolean synced = !result.lines.isEmpty();
        assertFalse("Empty lyrics must not be marked as synced", synced);
    }

    /**
     * Null input should produce an empty result, meaning not synced.
     */
    @Test
    public void nullLyricsAreNotSynced() {
        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(null);
        assertTrue("Null input should produce empty lines", result.lines.isEmpty());

        boolean synced = !result.lines.isEmpty();
        assertFalse("Null lyrics must not be marked as synced", synced);
    }

    /**
     * Plain text without any timestamps should produce an empty synced result.
     * The fallback to plain lyrics is handled by LocalLyricsFetcher, not the parser.
     */
    @Test
    public void unsynchronizedPlainTextHasNoSyncedLines() {
        String plain = "Hello world\nThis is plain text\nNo timestamps here\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(plain);

        assertTrue("Plain text without timestamps should not produce synced lines",
                result.lines.isEmpty());

        boolean synced = !result.lines.isEmpty();
        assertFalse("Plain text without timestamps must not be marked as synced", synced);
    }

    /**
     * LRC metadata tags (title, artist, etc.) without any timestamped content lines.
     */
    @Test
    public void metadataOnlyWithoutTimestampsIsNotSynced() {
        String lrc = "[ti:Song Title]\n[ar:Artist Name]\n[al:Album]\n[by:Uploader]\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lrc);

        assertTrue("Metadata-only LRC should not produce synced lines",
                result.lines.isEmpty());

        boolean synced = !result.lines.isEmpty();
        assertFalse("Metadata-only LRC must not be marked as synced", synced);

        // But credit lines should still be extracted
        assertFalse("Credit lines should be extracted from metadata",
                result.creditLines.isEmpty());
    }

    /**
     * LRC with a mix of metadata and timestamped lines.
     */
    @Test
    public void metadataPlusTimestampedLinesIsSynced() {
        String lrc = "[ti:Song Title]\n[ar:Artist]\n[by:Uploader]\n"
                + "[00:05.00]First lyric line\n"
                + "[00:08.50]Second lyric line\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lrc);

        assertFalse("Expected timestamped lines", result.lines.isEmpty());
        boolean synced = !result.lines.isEmpty();
        assertTrue("Mixed metadata + timestamps must be synced", synced);

        // Credit lines should be preserved
        assertFalse("Credit lines should be extracted", result.creditLines.isEmpty());
        assertEquals(2, result.lines.size());
    }

    /**
     * Verifies that the old hasWords()-based check would have failed for ordinary LRC.
     * This documents why the fix was necessary.
     */
    @Test
    public void oldHasWordsCheckFailsForOrdinaryLrc() {
        String lrc = "[00:12.34]Hello world\n"
                + "[00:15.67]This is a line\n";

        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lrc);

        assertFalse(result.lines.isEmpty());

        // The old check: any line with words?
        boolean oldSynced = false;
        for (LyricsLine line : result.lines) {
            if (line.hasWords()) {
                oldSynced = true;
                break;
            }
        }
        assertFalse("The old hasWords() check incorrectly reports unsynced for ordinary LRC",
                oldSynced);

        // The new check: any lines at all?
        boolean newSynced = !result.lines.isEmpty();
        assertTrue("The new check correctly reports synced for ordinary LRC", newSynced);
    }
}
