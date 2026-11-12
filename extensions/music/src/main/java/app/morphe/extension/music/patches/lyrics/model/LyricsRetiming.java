/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * When each line counts as sung for the highlight and the scroll: overlapping lines are grouped
 * so they stay lit together, and a line keeps its highlight into the pause after it.
 */
@SuppressWarnings("unused")
public final class LyricsRetiming {
    /** Lines overlapping by more than this are sung together. */
    private static final long RETIMING_OVERLAP_THRESHOLD_MS = 250L;
    private static final long RETIMING_GAP_THRESHOLD_MS = 1L;
    /** How far at most a line's highlight reaches into the pause after it. */
    private static final long RETIMING_MAX_EXTENSION_MS = 1300L;
    /** How far the highlight of the last line reaches past its end. */
    private static final long LAST_LINE_EXTENSION_MS = 600L;

    /** A pause of this length after a line with no end means the line ended before the pause. */
    public static final long LONG_PAUSE_MS = 7000L;
    /** A line with no end and no long pause after it is taken to last until the next line. */
    public static final long ASSUMED_LINE_END_MAX_GAP_MS = LONG_PAUSE_MS + 4000L;
    private static final long MIN_VOCAL_HOLD_MS = 5000L;
    private static final long VOCAL_HOLD_PER_CHAR_MS = 180L;

    public static final long SCROLL_LOOK_AHEAD_MIN_MS = 350L;
    public static final long SCROLL_LOOK_AHEAD_MAX_MS = 500L;
    private static final long SCROLL_ANIMATION_MAX_DURATION_MS = 450L;
    private static final float SCROLL_DELAY_INCREMENT_RATIO = 0.1f;
    public static final long HIGHLIGHT_LOOK_AHEAD_MS = 190L;

    @SuppressWarnings("ClassCanBeRecord")
    public static final class RetimedEntry {
        public final LyricsLine line;
        public final long startMs;
        public final long actualEndTimeMs;
        public final long adjustedEndTimeMs;
        public final boolean isSecondary;

        public RetimedEntry(LyricsLine line, long startMs, long actualEndTimeMs, long adjustedEndTimeMs, boolean isSecondary) {
            this.line = line;
            this.startMs = startMs;
            this.actualEndTimeMs = actualEndTimeMs;
            this.adjustedEndTimeMs = adjustedEndTimeMs;
            this.isSecondary = isSecondary;
        }

        public long effectiveEndMs() {
            return Math.max(actualEndTimeMs, adjustedEndTimeMs);
        }
    }

    private LyricsRetiming() {
    }

    public static long calculateScrollDuration(long scrollLookAheadMs) {
        return Math.min(SCROLL_ANIMATION_MAX_DURATION_MS, Math.max(1L, scrollLookAheadMs));
    }

    /** {@return how much later each further line starts moving in a scroll of this duration} */
    public static float calculateScrollDelayIncrement(long scrollDurationMs) {
        return scrollDurationMs * SCROLL_DELAY_INCREMENT_RATIO;
    }

    /**
     * {@return where a line with no end of its own is taken to end, given where the next
     * line starts}
     */
    public static long estimateEndMs(LyricsLine line, long nextStartMs) {
        final long startMs = line.startTimeMs();
        final long vocalHold = Math.max(MIN_VOCAL_HOLD_MS,
                (line.text() != null ? line.text().length() : 10) * VOCAL_HOLD_PER_CHAR_MS);
        if (nextStartMs == LyricsLine.NO_TIME) {
            return startMs + vocalHold;
        }
        if (nextStartMs - startMs < ASSUMED_LINE_END_MAX_GAP_MS) {
            return nextStartMs;
        }
        return Math.min(startMs + vocalHold, nextStartMs - LONG_PAUSE_MS);
    }

    public static long[] buildAdjustedEndMaxByIndex(@Nullable List<RetimedEntry> retimedLyrics) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return new long[0];
        }
        final int size = retimedLyrics.size();
        long[] maxEnd = new long[size];
        long currentMax = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            RetimedEntry r = retimedLyrics.get(i);
            currentMax = Math.max(currentMax, r.effectiveEndMs());
            maxEnd[i] = currentMax;
        }
        return maxEnd;
    }

    public static int findLastStartedLineIndex(@Nullable List<RetimedEntry> retimedLyrics, long positionMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = retimedLyrics.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (retimedLyrics.get(mid).startMs <= positionMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    public static List<Integer> findActiveRetimedLineIndices(
            @Nullable List<RetimedEntry> retimedLyrics,
            @Nullable long[] adjustedEndMaxByIndex,
            long positionMs,
            long endToleranceMs) {
        if (retimedLyrics == null || retimedLyrics.isEmpty() || adjustedEndMaxByIndex == null) {
            return Collections.emptyList();
        }
        int lastStartedIndex = findLastStartedLineIndex(retimedLyrics, positionMs);
        if (lastStartedIndex < 0) {
            return Collections.emptyList();
        }

        List<Integer> activeIndices = new ArrayList<>();
        int index = lastStartedIndex;
        while (index >= 0 && index < adjustedEndMaxByIndex.length
                && adjustedEndMaxByIndex[index] + endToleranceMs >= positionMs) {
            if (positionMs <= retimedLyrics.get(index).effectiveEndMs() + endToleranceMs) {
                activeIndices.add(index);
            }
            index--;
        }

        Collections.reverse(activeIndices);
        return activeIndices;
    }

    private static boolean areConsecutiveMainLines(
            List<RetimedEntry> retimedLyrics,
            int prevIdx,
            int currIdx) {
        if (currIdx <= prevIdx) return false;
        if (currIdx - prevIdx == 1) return true;
        for (int m = prevIdx + 1; m < currIdx && m < retimedLyrics.size(); m++) {
            if (!retimedLyrics.get(m).isSecondary) {
                return false;
            }
        }
        return true;
    }

    public static int calculatePrimaryRetimedLineIndex(
            @Nullable List<RetimedEntry> retimedLyrics,
            @Nullable List<Integer> activeIndices,
            long positionMs,
            int lastActiveIndex) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return -1;
        }

        int primaryIndex = (activeIndices != null && !activeIndices.isEmpty())
                ? activeIndices.get(activeIndices.size() - 1) : -1;
        if (primaryIndex != -1) {
            if (positionMs > retimedLyrics.get(primaryIndex).effectiveEndMs() + 10L) {
                primaryIndex = -1;
            }
        }

        if (primaryIndex != -1) {
            int groupEnd = activeIndices.size() - 1;
            int groupStart = groupEnd;
            while (groupStart > 0 && areConsecutiveMainLines(
                    retimedLyrics, activeIndices.get(groupStart - 1), activeIndices.get(groupStart))) {
                groupStart--;
            }

            int candidateIndex = activeIndices.get(Math.max(groupStart, groupEnd - 2));
            boolean lastPrimaryStillActive = activeIndices.contains(lastActiveIndex);
            primaryIndex = (candidateIndex < lastActiveIndex && lastPrimaryStillActive)
                    ? lastActiveIndex : candidateIndex;
        } else {
            long firstStart = retimedLyrics.get(0).startMs;
            if (positionMs < firstStart) {
                primaryIndex = 0;
            } else {
                int lastStarted = findLastStartedLineIndex(retimedLyrics, positionMs);
                primaryIndex = Math.max(0, Math.min(retimedLyrics.size() - 1, lastStarted));
            }
        }

        RetimedEntry currentPrimaryRetimed = (lastActiveIndex >= 0 && lastActiveIndex < retimedLyrics.size())
                ? retimedLyrics.get(lastActiveIndex) : null;
        RetimedEntry candidateRetimed = (primaryIndex >= 0 && primaryIndex < retimedLyrics.size())
                ? retimedLyrics.get(primaryIndex) : null;

        if (primaryIndex > lastActiveIndex
                && candidateRetimed != null
                && currentPrimaryRetimed != null
                && candidateRetimed.adjustedEndTimeMs == currentPrimaryRetimed.adjustedEndTimeMs
                && activeIndices != null && activeIndices.size() <= 3) {
            return lastActiveIndex;
        } else {
            return primaryIndex;
        }
    }

    /**
     * @param gapAfterIndex   Lines followed by a pause that shows its own indicator, which the
     *                        highlight of the line then does not reach into.
     * @param isSecondaryLine Lines that are background vocals of the main line before them.
     */
    public static List<RetimedEntry> retimeLyrics(
            @Nullable List<LyricsLine> lyrics,
            @Nullable Set<Integer> gapAfterIndex,
            @Nullable boolean[] isSecondaryLine) {
        if (lyrics == null || lyrics.isEmpty()) {
            return Collections.emptyList();
        }

        final int len = lyrics.size();
        long[] originalEndMs = new long[len];
        long[] newEndMs = new long[len];
        long[] startMs = new long[len];
        boolean[] secFlags = new boolean[len];

        for (int i = 0; i < len; i++) {
            LyricsLine e = lyrics.get(i);
            secFlags[i] = (isSecondaryLine != null && i < isSecondaryLine.length && isSecondaryLine[i])
                    || e.isBG();
            startMs[i] = e.startTimeMs();
            long end = e.sungEndMs();
            if (end == LyricsLine.NO_TIME) {
                long nextDiffStart = LyricsLine.NO_TIME;
                for (int j = i + 1; j < len; j++) {
                    if (lyrics.get(j).startTimeMs() > startMs[i]) {
                        nextDiffStart = lyrics.get(j).startTimeMs();
                        break;
                    }
                }
                end = estimateEndMs(e, nextDiffStart);
            }
            originalEndMs[i] = end;
            newEndMs[i] = end;
        }

        int i = 0;
        while (i < len) {
            int clusterEnd = i;
            long maxMainEndInRange = originalEndMs[i];

            while (clusterEnd < len - 1) {
                int nextIdx = clusterEnd + 1;
                if (secFlags[nextIdx]) {
                    clusterEnd++;
                    maxMainEndInRange = Math.max(maxMainEndInRange, originalEndMs[clusterEnd]);
                    continue;
                }
                LyricsLine next = lyrics.get(nextIdx);
                long nextStart = next.startTimeMs();
                long overlap = maxMainEndInRange - nextStart;
                if (overlap > RETIMING_OVERLAP_THRESHOLD_MS) {
                    clusterEnd++;
                    maxMainEndInRange = Math.max(maxMainEndInRange, originalEndMs[clusterEnd]);
                } else {
                    break;
                }
            }

            long clusterBaseEnd = originalEndMs[i];
            for (int k = i; k <= clusterEnd; k++) {
                clusterBaseEnd = Math.max(clusterBaseEnd, originalEndMs[k]);
            }
            long clusterFinalEnd = clusterBaseEnd;

            if (clusterEnd + 1 < len) {
                long nextStart = lyrics.get(clusterEnd + 1).startTimeMs();
                long gap = nextStart - clusterBaseEnd;
                boolean hasManualGap = (gapAfterIndex != null && gapAfterIndex.contains(clusterEnd));
                if (gap > RETIMING_GAP_THRESHOLD_MS && !hasManualGap) {
                    clusterFinalEnd += Math.min(RETIMING_MAX_EXTENSION_MS, gap);
                }
            } else {
                clusterFinalEnd += LAST_LINE_EXTENSION_MS;
            }

            for (int j = i; j <= clusterEnd; j++) {
                if (secFlags[j]) {
                    newEndMs[j] = originalEndMs[j];
                    continue;
                }
                long jEffEnd = originalEndMs[j];
                for (int s = j + 1; s <= clusterEnd && secFlags[s]; s++) {
                    jEffEnd = Math.max(jEffEnd, originalEndMs[s]);
                }
                long cutoff = Long.MIN_VALUE;
                long prevEffEnd = jEffEnd;
                for (int k = j + 1; k <= clusterEnd; k++) {
                    if (secFlags[k]) {
                        continue;
                    }
                    boolean jClearsK = (jEffEnd - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    boolean chainBrokenAtK = (prevEffEnd - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    if (jClearsK || chainBrokenAtK) {
                        cutoff = startMs[k];
                        break;
                    }
                    long kEffEnd = originalEndMs[k];
                    for (int s = k + 1; s <= clusterEnd && secFlags[s]; s++) {
                        kEffEnd = Math.max(kEffEnd, originalEndMs[s]);
                    }
                    prevEffEnd = kEffEnd;
                }
                newEndMs[j] = cutoff != Long.MIN_VALUE ? cutoff : clusterFinalEnd;
            }

            i = clusterEnd + 1;
        }

        List<RetimedEntry> result = new ArrayList<>(len);
        long[] finalAdjEnd = new long[len];
        for (int idx = 0; idx < len; idx++) {
            long candidateAdj = (Math.abs(newEndMs[idx] - originalEndMs[idx]) > RETIMING_GAP_THRESHOLD_MS)
                    ? newEndMs[idx] : originalEndMs[idx];
            finalAdjEnd[idx] = Math.max(originalEndMs[idx], candidateAdj);
        }
        for (int idx = 0; idx < len; idx++) {
            if (!secFlags[idx]) {
                long mainAdj = finalAdjEnd[idx];
                int nextMainIdx = idx + 1;
                while (nextMainIdx < len && secFlags[nextMainIdx]) {
                    nextMainIdx++;
                }
                for (int sIdx = idx + 1; sIdx < nextMainIdx; sIdx++) {
                    mainAdj = Math.max(mainAdj, finalAdjEnd[sIdx]);
                }
                finalAdjEnd[idx] = mainAdj;
                // Secondary lines belong to parent main line, so they share the same adjusted lifetime.
                for (int sIdx = idx + 1; sIdx < nextMainIdx; sIdx++) {
                    finalAdjEnd[sIdx] = mainAdj;
                }
            }
        }
        for (int idx = 0; idx < len; idx++) {
            result.add(new RetimedEntry(lyrics.get(idx), startMs[idx], originalEndMs[idx], finalAdjEnd[idx], secFlags[idx]));
        }
        return result;
    }
}
