/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.SectionDotsMotion;

/**
 * Which line the miniplayer and the media session show at a playback position.
 * Instrumental gaps are the same ones the lyrics panel shows its section dots for.
 */
public final class MiniLyricsTimeline {

    public record Vocal(LyricsLine line, int index, long startMs, long endMs) {
    }

    /**
     * @param previous The line sung before the gap, or null if the gap is before the first line.
     */
    public record Gap(long startMs, long endMs, @Nullable Vocal previous) {
    }

    /**
     * At most one of vocal and gap is set.
     *
     * @param previous If neither is set, the line that started last, or null if none did yet.
     */
    public record Selection(@Nullable Vocal vocal, @Nullable Gap gap, @Nullable Vocal previous) {
    }

    private static final int MUSICAL_NOTE = "🎵".codePointAt(0);
    private static final int MULTIPLE_MUSICAL_NOTES = "🎶".codePointAt(0);

    private final List<Vocal> vocals = new ArrayList<>();
    private final List<Gap> gaps = new ArrayList<>();
    /** The latest end time of the vocals up to and including each index. */
    private final long[] prefixEnds;
    private final long durationMs;

    public MiniLyricsTimeline(List<LyricsLine> lines, long durationMs) {
        this.durationMs = durationMs;

        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line.startTimeMs() < 0 || isInstrumentalMarker(line.text())) continue;

            long end = line.endTimeMs();
            if (line.hasWordTimings()) {
                long wordEnd = -1;
                for (Word word : line.words()) {
                    if (word.endMs() > word.startMs()) wordEnd = Math.max(wordEnd, word.endMs());
                }
                if (wordEnd > line.startTimeMs()) end = wordEnd;
            }
            if (end <= line.startTimeMs()) {
                // No end time, so the line lasts until the next one starts.
                end = durationMs > line.startTimeMs() ? durationMs : Long.MAX_VALUE;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (!line.isBG() && lines.get(j).isBG()) continue;
                    long next = lines.get(j).startTimeMs();
                    if (next > line.startTimeMs()) {
                        end = Math.min(end, next);
                        break;
                    }
                }
            }
            if (durationMs > 0) end = Math.min(end, durationMs);
            if (end > line.startTimeMs()) vocals.add(new Vocal(line, i, line.startTimeMs(), end));
        }
        vocals.sort(Comparator.comparingLong(Vocal::startMs).thenComparingInt(Vocal::index));

        // A provider line of only music notes marks an instrumental gap as well.
        List<SectionDotsMotion.Gap> classified = new ArrayList<>(SectionDotsMotion.computeGaps(lines));
        for (int i = 0, size = lines.size(); i < size; i++) {
            LyricsLine line = lines.get(i);
            if (line.startTimeMs() < 0 || !isInstrumentalMarker(line.text())) continue;

            long end = i + 1 < lines.size() ? lines.get(i + 1).startTimeMs() : durationMs;
            if (end <= line.startTimeMs()) end = line.endTimeMs();
            if (end > line.startTimeMs()) {
                classified.add(new SectionDotsMotion.Gap(i, line.startTimeMs(), end));
            }
        }

        // Gaps only cover time no line is sung in.
        prefixEnds = new long[vocals.size()];
        long cursor = 0;
        Vocal previous = null;
        for (int i = 0; i < vocals.size(); i++) {
            Vocal vocal = vocals.get(i);
            addGaps(classified, cursor, vocal.startMs(), previous);
            if (vocal.endMs() >= cursor) {
                cursor = vocal.endMs();
                previous = vocal;
            }
            prefixEnds[i] = cursor;
        }
        addGaps(classified, cursor, durationMs > 0 ? durationMs : Long.MAX_VALUE, previous);

        // A note line can overlap a gap of the panel. Merge them so only one animation plays.
        gaps.sort(Comparator.comparingLong(Gap::startMs));
        for (int i = 1; i < gaps.size(); ) {
            Gap before = gaps.get(i - 1);
            Gap next = gaps.get(i);
            if (next.startMs() <= before.endMs()) {
                gaps.set(i - 1, new Gap(before.startMs(), Math.max(before.endMs(), next.endMs()),
                        before.previous()));
                gaps.remove(i);
            } else {
                i++;
            }
        }
    }

    private void addGaps(List<SectionDotsMotion.Gap> classified, long start, long end,
                         @Nullable Vocal previous) {
        for (SectionDotsMotion.Gap gap : classified) {
            long from = Math.max(start, gap.gapStartMs());
            long to = Math.min(end, gap.gapEndMs());
            if (from < to) gaps.add(new Gap(from, to, previous));
        }
    }

    /**
     * If a line is empty or only has music notes, such as the ♪♪♪ some providers use.
     */
    public static boolean isInstrumentalMarker(String text) {
        if (text.trim().isEmpty()) return true;

        boolean note = false;
        for (int at = 0; at < text.length(); ) {
            final int cp = text.codePointAt(at);
            at += Character.charCount(cp);
            // 0xFE0F is the emoji presentation selector.
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFE0F) continue;
            if ((cp >= '♩' && cp <= '♬') || cp == MUSICAL_NOTE || cp == MULTIPLE_MUSICAL_NOTES) {
                note = true;
            } else {
                return false;
            }
        }
        return note;
    }

    public Selection at(long positionMs) {
        long position = Math.max(0, positionMs);
        if (durationMs > 0) position = Math.min(position, durationMs - 1);

        int low = 0;
        int high = gaps.size() - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            Gap gap = gaps.get(mid);
            if (position < gap.startMs()) high = mid - 1;
            else if (position >= gap.endMs()) low = mid + 1;
            else return new Selection(null, gap, null);
        }

        // The last vocal that started at or before the position.
        low = 0;
        high = vocals.size() - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            if (vocals.get(mid).startMs() <= position) low = mid + 1;
            else high = mid - 1;
        }

        // Prefer a lead vocal over a background vocal sung at the same time.
        Vocal background = null;
        for (int i = high; i >= 0 && prefixEnds[i] > position; i--) {
            Vocal vocal = vocals.get(i);
            if (vocal.endMs() <= position) continue;
            if (!vocal.line().isBG()) return new Selection(vocal, null, null);
            if (background == null) background = vocal;
        }
        if (background != null) return new Selection(background, null, null);

        return new Selection(null, null, high >= 0 ? vocals.get(high) : null);
    }
}
