/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;

/**
 * Shared lyric fixtures for the unit tests, so each test reads the track and lines it means
 * rather than rebuilding them inline.
 */
public final class LyricsFixtures {

    private LyricsFixtures() {
    }

    public static TrackInfo track(String title, String artist) {
        return new TrackInfo(title, artist, null, 0);
    }

    public static TrackInfo track(String title, String artist, String album, int durationSeconds) {
        return new TrackInfo(title, artist, album, durationSeconds);
    }

    /** A line with no word timings and no end time. */
    public static LyricsLine line(long startMs, String text) {
        return new LyricsLine(startMs, text);
    }

    /** A line with word timings whose words cover the text exactly. */
    public static LyricsLine wordTimedLine(long startMs, long endMs, String text) {
        List<Word> words = new ArrayList<>();
        long cursor = startMs;
        long span = (endMs - startMs) / Math.max(1, countWords(text));
        for (String w : text.split(" ")) {
            long wordEnd = cursor + span;
            words.add(new Word(cursor, wordEnd, w));
            cursor = wordEnd;
        }
        return new LyricsLine(startMs, endMs, text, words, null, false, false, null);
    }

    private static int countWords(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                count++;
            }
        }
        return Math.max(1, count);
    }

    /** A synced lyrics object whose lines are {@code [line0, line1, ...]}. */
    public static Lyrics synced(LyricsLine... lines) {
        return new Lyrics(List.of(lines), "TestProvider", true,
                null, null, null, null, null, null, null);
    }

    /** An unsynced plain-text lyrics object. */
    public static Lyrics plain(String... texts) {
        List<LyricsLine> lines = new ArrayList<>();
        for (String text : texts) {
            lines.add(line(LyricsLine.NO_TIME, text));
        }
        return new Lyrics(lines, "TestProvider", false,
                null, null, null, null, null, null, null);
    }
}
