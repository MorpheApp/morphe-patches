/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;

/**
 * A single lyrics line.
 *
 * @param startTimeMs Start time in milliseconds, or {@link #NO_TIME} for unsynced lyrics.
 * @param endTimeMs   End time in milliseconds, or {@link #NO_TIME} when unknown.
 * @param text        Concatenation of every {@link #words()} entry, so translation and
 *                    copy stay correct.
 * @param words       Word level timing, empty when only the line is synced.
 * @param agentId     Agent ID from {@code ttm:agent} or an enhanced LRC marker, or null.
 * @param isDuet      Right-aligned duet line, from agent alternation or number parity.
 * @param isBG        Background vocal line.
 * @param songPart    Song section label (e.g. "Verse", "Chorus"), or {@code null}.
 */
public record LyricsLine(long startTimeMs, long endTimeMs, String text, List<Word> words,
                         @Nullable String agentId, boolean isDuet, boolean isBG,
                         @Nullable String songPart) {

    public static final long NO_TIME = -1;

    public LyricsLine {
        words = words == null ? List.of() : words;
        text = text == null ? "" : text;
        if (words.size() > 1 && !ScriptDetector.contains(text, Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)) {
            boolean hasEndedWithSpace = false;
            for (Word w : words) {
                if (w.endsWithSpace()) {
                    hasEndedWithSpace = true;
                    break;
                }
            }
            if (text.indexOf(' ') < 0) {
                if (hasEndedWithSpace) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < words.size(); i++) {
                        Word w = words.get(i);
                        sb.append(w.text());
                        if (w.endsWithSpace() && i < words.size() - 1) {
                            sb.append(' ');
                        }
                    }
                    text = sb.toString();
                } else {
                    List<Word> updated = new ArrayList<>(words.size());
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < words.size(); i++) {
                        Word w = words.get(i);
                        if (i > 0) sb.append(' ');
                        sb.append(w.text());
                        boolean sp = (i < words.size() - 1);
                        updated.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), sp));
                    }
                    words = updated;
                    text = sb.toString();
                }
            } else if (!hasEndedWithSpace) {
                List<Word> updated = new ArrayList<>(words.size());
                int cursor = 0;
                for (int i = 0; i < words.size(); i++) {
                    Word w = words.get(i);
                    int idx = text.indexOf(w.text(), cursor);
                    boolean sp = false;
                    if (idx >= 0) {
                        int end = idx + w.text().length();
                        sp = (end < text.length() && Character.isWhitespace(text.charAt(end)));
                        cursor = end;
                    } else {
                        sp = (i < words.size() - 1);
                    }
                    updated.add(new Word(w.startMs(), w.endMs(), w.text(), w.romaji(), sp));
                }
                words = updated;
            }
        }
        words = Collections.unmodifiableList(words);
    }

    public LyricsLine(long startTimeMs, String text, List<Word> words,
                      @Nullable String agentId, boolean isDuet, boolean isBG) {
        this(startTimeMs, NO_TIME, text, words, agentId, isDuet, isBG, null);
    }

    public LyricsLine(long startTimeMs, String text, List<Word> words) {
        this(startTimeMs, NO_TIME, text, words, null, false, false, null);
    }

    public LyricsLine(long startTimeMs, long endTimeMs, String text, List<Word> words) {
        this(startTimeMs, endTimeMs, text, words, null, false, false, null);
    }

    public LyricsLine(long startTimeMs, String text) {
        this(startTimeMs, NO_TIME, text, List.of(), null, false, false, null);
    }

    public boolean hasWords() {
        return !words.isEmpty();
    }

    /**
     * Whether the words say more than the line itself does.
     *
     * <p>A lone word covering the whole line, or one that never ends, is the line's own timing
     * written back out as a word. Counting it as word sync would have the line read as karaoke
     * while it can only ever be sung in one go, and would rank it above lyrics that really do
     * carry word timings.
     */
    public boolean hasWordTimings() {
        if (words.isEmpty()) {
            return false;
        }
        if (words.size() == 1) {
            Word single = words.get(0);
            if (single.endMs() <= single.startMs()) {
                return false;
            }
            if (hasMultipleWords(text) && compactText(single.text()).equalsIgnoreCase(compactText(text))) {
                return false;
            }
        }
        return true;
    }

    private static String compactText(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    // ponytail: whitespace word counting; upgrade to BreakIterator if space-less scripts mix dummy spans.
    private static boolean hasMultipleWords(String value) {
        boolean inWord = false;
        int wordCount = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                wordCount++;
                if (wordCount > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return "LyricsLine{" + startTimeMs + "-" + endTimeMs + ", '" + text + "', words=" + words.size()
                + ", agent=" + agentId + ", duet=" + isDuet + ", bg=" + isBG
                + ", songPart=" + songPart + "}";
    }
}
