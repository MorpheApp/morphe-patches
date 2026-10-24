/*
 * Copyright 2026 Morphe contributors.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches.lyrics.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.SectionDotsMotion;

/** Mini timing. Gap decisions come from the panel, not a second silence detector. */
public final class MiniLyricsTimeline {
    public record Vocal(LyricsLine line, int index, long startMs, long endMs) { }
    public record Gap(long startMs, long endMs, Vocal previous) { }
    public record Selection(Vocal vocal, Gap gap) { }
    private final List<Vocal> vocals;
    private final List<Gap> gaps;
    private final long[] prefixEnds;
    private final long durationMs;

    public MiniLyricsTimeline(List<LyricsLine> lines, long durationMs) {
        this.durationMs = durationMs;
        vocals = new ArrayList<>(); gaps = new ArrayList<>();
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
                end = durationMs > line.startTimeMs() ? durationMs : Long.MAX_VALUE;
                for (int j = i + 1; j < lines.size(); j++) {
                    if (!line.isBG() && lines.get(j).isBG()) continue;
                    long next = lines.get(j).startTimeMs();
                    if (next > line.startTimeMs()) { end = Math.min(end, next); break; }
                }
            }
            if (durationMs > 0) end = Math.min(end, durationMs);
            if (end > line.startTimeMs()) vocals.add(new Vocal(line, i, line.startTimeMs(), end));
        }
        vocals.sort(Comparator.comparingLong(Vocal::startMs).thenComparingInt(Vocal::index));
        // Reuse the panel's gap timing. A note-only provider line is an explicit marker too.
        List<SectionDotsMotion.Gap> classified = new ArrayList<>(SectionDotsMotion.computeGaps(lines));
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line.startTimeMs() < 0 || !isInstrumentalMarker(line.text())) continue;
            long end = i + 1 < lines.size() ? lines.get(i + 1).startTimeMs() : durationMs;
            if (end <= line.startTimeMs()) end = line.endTimeMs();
            if (end > line.startTimeMs()) classified.add(new SectionDotsMotion.Gap(i, line.startTimeMs(), end));
        }
        prefixEnds = new long[vocals.size()];
        long cursor = 0; Vocal previous = null;
        for (int i = 0; i < vocals.size(); i++) {
            Vocal vocal = vocals.get(i);
            addGaps(classified, cursor, vocal.startMs(), previous);
            if (vocal.endMs() >= cursor) { cursor = vocal.endMs(); previous = vocal; }
            prefixEnds[i] = cursor;
        }
        addGaps(classified, cursor, durationMs > 0 ? durationMs : Long.MAX_VALUE, previous);
        gaps.sort(Comparator.comparingLong(Gap::startMs));
        // Explicit markers may overlap a panel gap; keep one interval, not two animations.
        for (int i = 1; i < gaps.size();) {
            Gap before = gaps.get(i - 1), next = gaps.get(i);
            if (next.startMs() <= before.endMs()) {
                gaps.set(i - 1, new Gap(before.startMs(), Math.max(before.endMs(), next.endMs()), before.previous()));
                gaps.remove(i);
            } else i++;
        }
    }

    private void addGaps(List<SectionDotsMotion.Gap> classified, long start, long end, Vocal previous) {
        for (SectionDotsMotion.Gap gap : classified) {
            long from = Math.max(start, gap.gapStartMs()), to = Math.min(end, gap.gapEndMs());
            if (from < to) gaps.add(new Gap(from, to, previous));
        }
    }

    /** Empty lines and music-note-only variants, including old parser-generated ♪♪♪. */
    public static boolean isInstrumentalMarker(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        boolean note = false;
        for (int at = 0; at < text.length();) {
            int cp = text.codePointAt(at); at += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFE0F) continue;
            if ((cp >= 0x2669 && cp <= 0x266C) || cp == 0x1F3B5 || cp == 0x1F3B6) note = true;
            else return false;
        }
        return note;
    }

    public Selection at(long positionMs) {
        long position = Math.max(0, positionMs);
        if (durationMs > 0) position = Math.min(position, durationMs - 1);
        int low = 0, high = gaps.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1; Gap gap = gaps.get(mid);
            if (position < gap.startMs()) high = mid - 1;
            else if (position >= gap.endMs()) low = mid + 1;
            else return new Selection(null, gap);
        }
        low = 0; high = vocals.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (vocals.get(mid).startMs() <= position) low = mid + 1; else high = mid - 1;
        }
        Vocal background = null;
        for (int i = high; i >= 0 && prefixEnds[i] > position; i--) {
            Vocal vocal = vocals.get(i);
            if (vocal.endMs() <= position) continue;
            if (!vocal.line().isBG()) return new Selection(vocal, null);
            if (background == null) background = vocal;
        }
        return new Selection(background, null);
    }
}
