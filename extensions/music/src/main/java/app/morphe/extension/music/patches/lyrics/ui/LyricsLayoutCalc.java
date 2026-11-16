/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;
import app.morphe.extension.music.settings.Settings;

/**
 * Which lines sit right-aligned, and the instrumental breaks long enough to show gap dots.
 */
final class LyricsLayoutCalc {

    private LyricsLayoutCalc() {
    }

    /** A line with no end of its own is taken to last at least this long, and at most the max. */
    static final long UNENDED_LINE_MIN_MS = 1000L;
    static final long UNENDED_LINE_MAX_MS = 4500L;
    /** The dots of a break show for at least this long before they close. */
    static final long MIN_GAP_DOTS_MS = 600L;

    /**
     * {@return which lines sit on the right: those in a right-to-left script, swapped for the
     * second singer of a duet}
     */
    static boolean[] rightAlignedLines(List<LyricsLine> lines, boolean[] isSecFlags) {
        boolean isDuetSong = false;
        for (LyricsLine l : lines) {
            if (l.isDuet()) {
                isDuetSong = true;
                break;
            }
        }
        boolean[] rightAligned = new boolean[lines.size()];
        int parentIdx = -1;
        for (int i = 0; i < rightAligned.length; i++) {
            if (!isSecFlags[i]) {
                parentIdx = i;
            }
            LyricsLine l = lines.get(i);
            final boolean lineRtl = ScriptDetector.isLineRtl(l.text());
            // A background vocal sits on its lead line's side.
            final boolean isDuetLine = l.isDuet()
                    || (isSecFlags[i] && parentIdx >= 0 && lines.get(parentIdx).isDuet());
            rightAligned[i] = isDuetSong ? lineRtl != isDuetLine : lineRtl;
        }
        return rightAligned;
    }

    /**
     * {@return the instrumental breaks long enough for dots: before the first line, and
     * between a lead line with its background vocals and the next lead line}
     */
    static List<GapInterval> buildGapIntervals(List<LyricsLine> lines, boolean[] isSecFlags,
                                               boolean[] lineRightAligned) {
        List<GapInterval> gaps = new ArrayList<>();
        final boolean forceCenter = Settings.LYRICS_FORCE_CENTER.get();
        int prevMainIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isSecFlags[i] && prevMainIdx >= 0) {
                continue;
            }
            final LyricsLine line = lines.get(i);
            final int prevIdx = prevMainIdx;
            prevMainIdx = i;
            final long currentStart = line.startTimeMs();
            if (currentStart == LyricsLine.NO_TIME) {
                continue;
            }

            final long gapStart;
            if (prevIdx < 0) {
                if (currentStart < LyricsRetiming.LONG_PAUSE_MS) {
                    continue;
                }
                gapStart = 0;
            } else {
                final LyricsLine prevLine = lines.get(prevIdx);
                final long prevStart = prevLine.startTimeMs();
                if (prevStart == LyricsLine.NO_TIME) {
                    continue;
                }
                long prevEnd = prevLine.sungEndMs();
                for (int s = prevIdx + 1; s < i; s++) {
                    final long secEnd = lines.get(s).sungEndMs();
                    if (secEnd != LyricsLine.NO_TIME) {
                        prevEnd = prevEnd == LyricsLine.NO_TIME ? secEnd : Math.max(prevEnd, secEnd);
                    }
                }
                final long gapBetweenStarts = currentStart - prevStart;
                // A line with no words and a next line close after it is not followed by a
                // break worth showing, whatever its end says.
                if (gapBetweenStarts < LyricsRetiming.ASSUMED_LINE_END_MAX_GAP_MS
                        && prevLine.words().isEmpty()) {
                    continue;
                }
                final long safeEnd;
                if (prevEnd != LyricsLine.NO_TIME) {
                    safeEnd = prevEnd;
                } else {
                    // A line with no end is sung for a while at least.
                    final long minSafeEnd = prevStart + Math.min(UNENDED_LINE_MAX_MS,
                            Math.max(UNENDED_LINE_MIN_MS, gapBetweenStarts / 2));
                    safeEnd = Math.max(LyricsRetiming.estimateEndMs(prevLine, currentStart), minSafeEnd);
                }
                if (currentStart - safeEnd < LyricsRetiming.LONG_PAUSE_MS) {
                    continue;
                }
                gapStart = safeEnd + GapLineView.LYRICS_GAP_START_LAG_MS;
            }
            final long gapEnd = Math.max(gapStart + MIN_GAP_DOTS_MS,
                    currentStart - GapLineView.GAP_EXIT_DURATION_MS);
            gaps.add(new GapInterval(gapStart, gapEnd, currentStart, i, lineRightAligned[i],
                    forceCenter || line.isCentered(), ScriptDetector.isLineRtl(line.text())));
        }
        return gaps;
    }
}
