/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsRetiming;

final class LyricsActiveLines {

    private LyricsActiveLines() {
    }

    static final long BG_LATE_START_MS = 550L;
    static final long BG_OPEN_LEAD_MS = 250L;
    static final long BG_EARLY_END_MS = 600L;
    static final long BG_LINGER_MS = 300L;

    // Captured so no view is read here.
    record LineSecondary(boolean isLineView, boolean isSecondary, int parentLineIndex) {
    }

    // Replaces direct view reads.
    record SecondaryMap(LineSecondary[] lines) {
    }

    record Context(List<LyricsRetiming.RetimedEntry> retimedLyrics,
                   long[] retimedAdjustedEndMax,
                   List<Boolean> lineIsBackground,
                   SecondaryMap secondary) {
    }

    static boolean isSecondaryLineAt(int index, Context ctx) {
        if (index <= 0) {
            return false;
        }
        final LineSecondary[] lines = ctx.secondary().lines();
        if (index < lines.length && lines[index].isLineView()) {
            return lines[index].isSecondary() && lines[index].parentLineIndex() >= 0;
        }
        if (index < ctx.retimedLyrics().size()) {
            return ctx.retimedLyrics().get(index).isSecondary();
        }
        return index < ctx.lineIsBackground().size()
                && Boolean.TRUE.equals(ctx.lineIsBackground().get(index));
    }

    static int leadLineIndex(int index, Context ctx) {
        int lead = index;
        final LineSecondary[] lines = ctx.secondary().lines();
        if (lead >= 0 && lead < lines.length
                && lines[lead].isLineView()
                && lines[lead].isSecondary() && lines[lead].parentLineIndex() >= 0) {
            lead = lines[lead].parentLineIndex();
        }
        while (lead > 0 && isSecondaryLineAt(lead, ctx)) {
            lead--;
        }
        return lead;
    }

    static List<Integer> leadLineIndices(Iterable<Integer> indices, Context ctx) {
        Set<Integer> leads = new TreeSet<>();
        for (int idx : indices) {
            final int lead = leadLineIndex(idx, ctx);
            if (lead >= 0 && lead < ctx.retimedLyrics().size()) {
                leads.add(lead);
            }
        }
        return new ArrayList<>(leads);
    }

    static Set<Integer> computeActiveLines(long currentTimeMs, Context ctx) {
        final List<LyricsRetiming.RetimedEntry> retimed = ctx.retimedLyrics();
        if (retimed.isEmpty()) {
            return Collections.emptySet();
        }
        final long scanTime = currentTimeMs + LyricsRetiming.HIGHLIGHT_LOOK_AHEAD_MS;
        List<Integer> scanned = LyricsRetiming.findActiveRetimedLineIndices(
                retimed, ctx.retimedAdjustedEndMax(), scanTime, 0L);
        Set<Integer> set = new TreeSet<>(scanned);
        for (int i : scanned) {
            final int lead = leadLineIndex(i, ctx);
            if (lead >= 0) {
                set.add(lead);
            }
        }

        final LineSecondary[] secLines = ctx.secondary().lines();
        for (int i = 0; i < secLines.length; i++) {
            final LineSecondary line = secLines[i];
            if (!line.isLineView() || !line.isSecondary()) {
                continue;
            }
            final int pIdx = line.parentLineIndex();
            if (pIdx < 0 || !set.contains(pIdx)) {
                continue;
            }
            boolean shouldActivateSec = true;
            if (i < retimed.size() && pIdx < retimed.size()) {
                LyricsRetiming.RetimedEntry secEntry = retimed.get(i);
                LyricsRetiming.RetimedEntry parEntry = retimed.get(pIdx);
                if (secEntry.startMs() > parEntry.startMs() + BG_LATE_START_MS
                        && scanTime < secEntry.startMs() - BG_OPEN_LEAD_MS) {
                    // Late vocal opens only shortly before it is sung.
                    shouldActivateSec = false;
                } else if (secEntry.actualEndTimeMs() > secEntry.startMs()
                        && secEntry.actualEndTimeMs() < parEntry.actualEndTimeMs() - BG_EARLY_END_MS
                        && currentTimeMs > secEntry.actualEndTimeMs() + BG_LINGER_MS) {
                    // Early-ended vocal closes again after it ends.
                    shouldActivateSec = false;
                }
            }
            if (shouldActivateSec) {
                set.add(i);
            } else {
                set.remove(i);
            }
        }
        return set;
    }

    static int computePrimaryActiveIndex(Set<Integer> activeSet, long currentTimeMs,
                                         Context ctx, int previousPrimaryActiveIndex) {
        final List<LyricsRetiming.RetimedEntry> retimed = ctx.retimedLyrics();
        if (retimed.isEmpty()) {
            return -1;
        }
        final int idx = leadLineIndex(LyricsRetiming.calculatePrimaryRetimedLineIndex(
                        retimed, leadLineIndices(activeSet, ctx), currentTimeMs, previousPrimaryActiveIndex),
                ctx);
        return Math.max(0, idx);
    }

    static long lineEndMs(List<LyricsLine> lines, int index, long lastWordEndMs, Context ctx) {
        long end = lines.get(index).endTimeMs();
        if (lastWordEndMs != LyricsLine.NO_TIME) {
            end = (end == LyricsLine.NO_TIME) ? lastWordEndMs : Math.max(end, lastWordEndMs);
        }
        if (end == LyricsLine.NO_TIME && index < ctx.retimedLyrics().size()) {
            end = ctx.retimedLyrics().get(index).actualEndTimeMs();
        }
        if (end == LyricsLine.NO_TIME) {
            end = (index + 1 < lines.size()) ? lines.get(index + 1).startTimeMs() : Long.MAX_VALUE;
        }
        return end;
    }

    static long currentPauseAfterLineMs(long pos, int refIdx, Context ctx) {
        final List<LyricsRetiming.RetimedEntry> retimed = ctx.retimedLyrics();
        if (retimed.isEmpty()) {
            return Long.MIN_VALUE;
        }
        List<Integer> curActive = LyricsRetiming.findActiveRetimedLineIndices(
                retimed, ctx.retimedAdjustedEndMax(), pos, 0L);
        final int currentIdx = leadLineIndex(LyricsRetiming.calculatePrimaryRetimedLineIndex(
                retimed, curActive, pos, Math.max(0, refIdx)), ctx);
        if (currentIdx < 0 || currentIdx >= retimed.size()) {
            return Long.MIN_VALUE;
        }
        long rawEnd = retimed.get(currentIdx).actualEndTimeMs();
        int nextMainIdx = currentIdx + 1;
        while (nextMainIdx < retimed.size() && isSecondaryLineAt(nextMainIdx, ctx)) {
            rawEnd = Math.max(rawEnd, retimed.get(nextMainIdx).actualEndTimeMs());
            nextMainIdx++;
        }
        return nextMainIdx < retimed.size()
                ? retimed.get(nextMainIdx).startMs() - rawEnd
                : Long.MIN_VALUE;
    }
}
