/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;

/**
 * Classifies each lyric line as a secondary line — a background vocal or an echo of the line it
 * follows — and decides whether a background vocal sings before its lead line rather than after.
 */
final class LyricsSectionTags {

    private LyricsSectionTags() {
    }

    /** A line sung again this soon after the one it echoes is that line's secondary line. */
    static final long ECHO_MAX_GAP_MS = 3500L;
    /** A background vocal starting this far ahead of its lead line is still its lead line's. */
    static final long BG_MAX_LEAD_MS = 2000L;
    /** A background vocal starting this far after its lead line is treated as its secondary line. */
    static final long BG_MAX_LAG_MS = 30000L;
    /** A background vocal ending this far past its lead line's end still counts as before it. */
    static final long BG_MAX_AFTER_LEAD_END_MS = 4000L;
    /** A background vocal starting this far ahead of its lead line's start still counts as before it. */
    static final long BG_MAX_AFTER_LEAD_START_MS = 15000L;

    static boolean isSecondaryLyricLine(int index, List<LyricsLine> lines) {
        if (index <= 0 || index >= lines.size()) {
            return false;
        }
        LyricsLine line = lines.get(index);
        boolean candidate = line.isBG() || LyricsBracketSplitter.isBracketedVocal(line.text());
        if (!candidate && !LyricsBracketSplitter.hasTagBrackets(line.text())) {
            LyricsLine prev = lines.get(index - 1);
            final long gap = line.startTimeMs() - prev.startTimeMs();
            if (gap >= 0 && gap <= ECHO_MAX_GAP_MS) {
                String cleanCurr = LRCParser.cleanTextForComparison(line.text());
                candidate = !cleanCurr.isEmpty()
                        && cleanCurr.equals(LRCParser.cleanTextForComparison(prev.text()));
            }
        }
        if (!candidate) {
            return false;
        }

        int parentIdx = index - 1;
        while (parentIdx >= 0) {
            LyricsLine p = lines.get(parentIdx);
            if (!p.isBG() && !LyricsBracketSplitter.isBracketedVocal(p.text())) {
                break;
            }
            parentIdx--;
        }
        if (parentIdx < 0) {
            return false;
        }

        if (line.isBG() && !LyricsBracketSplitter.hasTagBrackets(line.text())) {
            return true;
        }

        LyricsLine parentLine = lines.get(parentIdx);
        if (line.startTimeMs() != LyricsLine.NO_TIME && parentLine.startTimeMs() != LyricsLine.NO_TIME) {
            final long timeDiff = line.startTimeMs() - parentLine.startTimeMs();
            if (timeDiff < -BG_MAX_LEAD_MS || timeDiff > BG_MAX_LAG_MS) {
                return false;
            }
            if (parentLine.endTimeMs() != LyricsLine.NO_TIME
                    && parentLine.endTimeMs() > parentLine.startTimeMs()) {
                return line.startTimeMs() <= Math.max(
                        parentLine.endTimeMs() + BG_MAX_AFTER_LEAD_END_MS,
                        parentLine.startTimeMs() + BG_MAX_AFTER_LEAD_START_MS);
            }
        }
        return true;
    }

    static boolean[] secondaryLineFlags(List<LyricsLine> lines) {
        boolean[] flags = new boolean[lines.size()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = isSecondaryLyricLine(i, lines);
        }
        return flags;
    }

    /** A background vocal sung ahead of its lead line sits above it. */
    static boolean isBgBeforeMain(List<LyricsLine> lines, int parentIdx, int bgIdx) {
        if (parentIdx < 0 || parentIdx >= lines.size() || bgIdx < 0 || bgIdx >= lines.size()) {
            return false;
        }
        LyricsLine mainLine = lines.get(parentIdx);
        LyricsLine bgLine = lines.get(bgIdx);
        final long mainStart = !mainLine.words().isEmpty()
                ? mainLine.words().get(0).startMs() : mainLine.startTimeMs();
        final long bgStart = !bgLine.words().isEmpty()
                ? bgLine.words().get(0).startMs() : bgLine.startTimeMs();
        return bgStart >= 0 && bgStart < mainStart;
    }
}
