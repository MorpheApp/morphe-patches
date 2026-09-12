/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lyrics of a single track, either synced (each line carries a timestamp) or plain.
 *
 * @param providerName Provider name shown to the user, for example {@code LRCLIB}.
 * @param translations Embedded translations keyed by BCP-47 language tag (e.g. {@code zh},
 *                     {@code en}, {@code zh-Hans}). A translation entry is a line-level list
 *                     aligned 1:1 with {@link #lines()}; an empty string marks a line with no
 *                     translation. {@code null} when the source ships none.
 * @param romanization Legacy single-language romanization, kept for backward compatibility.
 * @param romanizations Multi-language romanizations keyed by BCP-47 language tag.
 * @param songwriters Songwriter/credits names extracted from TTML metadata.
 * @param rawFormat Decrypted raw lyrics text in the provider's original format (e.g. LRC, QRC, TTML).
 * @param formatType File extension for the raw format (e.g. {@code "lrc"}, {@code "qrc"}, {@code "ttml"}).
 * @param sourceUrl Optional URL to the song page on the provider's platform, opened when the
 *                  source label is clicked.
 */
public record Lyrics(List<LyricsLine> lines, String providerName, boolean synced,
                     @Nullable List<LyricsLine> romanization,
                     @Nullable Map<String, List<LyricsLine>> translations,
                     @Nullable Map<String, List<LyricsLine>> romanizations,
                     @Nullable List<String> songwriters,
                     @Nullable String rawFormat,
                     @Nullable String formatType,
                     @Nullable String sourceUrl) {

    /** Marker for a track that was looked up successfully but has no lyrics anywhere. */
    public static final Lyrics NOT_FOUND = new Lyrics(Collections.emptyList(), "", false,
            null, null, null, null, null, null, null);

    public static final String CAPTIONS_PROVIDER = "Captions";

    public Lyrics {
        lines = Collections.unmodifiableList(lines);
        romanization = (romanization == null) ? null : Collections.unmodifiableList(romanization);
        translations = (translations == null) ? null : unmodifiableTranslations(translations);
        romanizations = (romanizations == null) ? null : unmodifiableTranslations(romanizations);
    }

    public Lyrics(List<LyricsLine> lines, String providerName, boolean synced,
                  @Nullable List<LyricsLine> romanization,
                  @Nullable Map<String, List<LyricsLine>> translations) {
        this(lines, providerName, synced, romanization, translations, null, null, null, null, null);
    }

    public Lyrics(List<LyricsLine> lines, String providerName, boolean synced,
                  @Nullable List<LyricsLine> romanization,
                  @Nullable Map<String, List<LyricsLine>> translations,
                  @Nullable Map<String, List<LyricsLine>> romanizations) {
        this(lines, providerName, synced, romanization, translations, romanizations, null, null, null, null);
    }

    public Lyrics(List<LyricsLine> lines, String providerName, boolean synced) {
        this(lines, providerName, synced, null, null, null, null, null, null, null);
    }

    public Lyrics(List<LyricsLine> lines, String providerName, boolean synced,
                  @Nullable List<LyricsLine> romanization) {
        this(lines, providerName, synced, romanization, null, null, null, null, null, null);
    }

    private static Map<String, List<LyricsLine>> unmodifiableTranslations(
            Map<String, List<LyricsLine>> in) {
        final Map<String, List<LyricsLine>> out = new HashMap<>(in.size());
        for (Map.Entry<String, List<LyricsLine>> entry : in.entrySet()) {
            final List<LyricsLine> value = entry.getValue();
            out.put(entry.getKey(), value == null ? null : Collections.unmodifiableList(value));
        }
        return Collections.unmodifiableMap(out);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Index of the line that should be highlighted at the given playback position,
     * or -1 if playback has not reached the first line yet.
     *
     * <p>No binary search: between two calls the position advances by one line at most,
     * so the hint answers almost every call in a few comparisons.
     *
     * @param positionMs Current playback position.
     * @param hintIndex  Index returned by the previous call, or -1.
     */
    public int indexForPosition(long positionMs, int hintIndex) {
        if (!synced || lines.isEmpty()) {
            return -1;
        }

        final int size = lines.size();
        int result = -1;

        // Fast path: still inside the hinted line, or moved into the next one.
        if (hintIndex >= 0 && hintIndex < size) {
            if (positionMs >= lines.get(hintIndex).startTimeMs()) {
                final int next = hintIndex + 1;
                if (next >= size || positionMs < lines.get(next).startTimeMs()) {
                    result = hintIndex;
                } else {
                    final int nextNext = next + 1;
                    if (nextNext >= size || positionMs < lines.get(nextNext).startTimeMs()) {
                        result = next;
                    }
                }
            }
        }

        if (result < 0) {
            for (int i = 0; i < size; i++) {
                if (lines.get(i).startTimeMs() > positionMs) {
                    break;
                }
                result = i;
            }
        }

        while (result >= 0 && lines.get(result).isBG()) {
            result--;
        }
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "Lyrics{" + providerName + ", synced=" + synced + ", lines=" + lines.size() + "}";
    }

    public static List<LyricsLine> fixAnomalousWordTimestamps(List<LyricsLine> lines) {
        final int size = lines.size();
        if (size == 0) return lines;
        final List<LyricsLine> out = new ArrayList<>(lines);
        for (int i = 0; i < size; i++) {
            final LyricsLine line = out.get(i);
            if (!line.hasWords()) continue;
            final List<Word> words = line.words();
            final int count = words.size();
            if (count == 0) continue;

            final long lineStart = line.startTimeMs();
            final long lineEnd = line.endTimeMs();

            long prevEnd = lineStart != LyricsLine.NO_TIME ? lineStart : 0;
            boolean changed = false;
            final Word[] fixed = new Word[count];

            for (int j = 0; j < count; j++) {
                final Word w = words.get(j);
                final boolean anomalous = (w.startMs() == 0 && lineStart > 0)
                        || (w.endMs() == 0 && w.startMs() > 0)
                        || (w.startMs() > 0 && w.startMs() == w.endMs())
                        || (w.startMs() > 0 && w.startMs() < lineStart - 500);

                if (!anomalous) {
                    prevEnd = w.endMs() > w.startMs() ? w.endMs() : w.startMs();
                    fixed[j] = w;
                    continue;
                }

                long nextStart = Long.MAX_VALUE;
                for (int k = j + 1; k < count; k++) {
                    final Word nw = words.get(k);
                    if (nw.startMs() > 0 && nw.endMs() > nw.startMs()) {
                        nextStart = nw.startMs();
                        break;
                    }
                }

                if (nextStart == Long.MAX_VALUE) {
                    nextStart = lineEnd != LyricsLine.NO_TIME
                            ? lineEnd : prevEnd + 800;
                }

                long newStart = Math.max(prevEnd, lineStart > 0 ? lineStart : 0);
                long newEnd = nextStart;
                if (newEnd <= newStart) {
                    newEnd = newStart + 50;
                }
                if (newEnd - newStart < 50) {
                    newEnd = newStart + 50;
                }

                fixed[j] = new Word(newStart, newEnd, w.text(), w.romaji(),
                        w.endsWithSpace());
                prevEnd = newEnd;
                changed = true;
            }

            if (changed) {
                out.set(i, new LyricsLine(line.startTimeMs(), line.endTimeMs(),
                        line.text(), List.of(fixed), line.agentId(), line.isDuet(),
                        line.isBG(), line.songPart()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static List<LyricsLine> clampLastWordEnds(List<LyricsLine> lines) {
        final int size = lines.size();
        if (size == 0) return lines;
        final List<LyricsLine> out = new ArrayList<>(lines);
        for (int i = 0; i < size; i++) {
            final LyricsLine line = out.get(i);
            if (!line.hasWords()) continue;
            final List<Word> words = line.words();
            final int lastIdx = words.size() - 1;
            final Word lastWord = words.get(lastIdx);
            long effectiveEnd = lastWord.endMs();
            if (i + 1 < size) {
                final long nextStart = out.get(i + 1).startTimeMs();
                if (nextStart != LyricsLine.NO_TIME) {
                    effectiveEnd = Math.min(effectiveEnd, nextStart);
                }
            }
            // Also respect the line's own endTimeMs if set.
            if (line.endTimeMs() != LyricsLine.NO_TIME) {
                effectiveEnd = Math.min(effectiveEnd, line.endTimeMs());
            }
            effectiveEnd = Math.max(effectiveEnd, lastWord.startMs() + 200);
            if (effectiveEnd != lastWord.endMs()) {
                final List<Word> newWords = new ArrayList<>(words);
                newWords.set(lastIdx, new Word(lastWord.startMs(), effectiveEnd,
                        lastWord.text(), lastWord.romaji(), lastWord.endsWithSpace()));
                out.set(i, new LyricsLine(line.startTimeMs(), line.endTimeMs(),
                        line.text(), newWords, line.agentId(), line.isDuet(),
                        line.isBG(), line.songPart()));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
