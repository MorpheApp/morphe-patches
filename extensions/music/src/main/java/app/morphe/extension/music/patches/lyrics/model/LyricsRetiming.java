package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * fix timing gap so lyrics highlight dont flicker or drop when there is small gap between lines.
 */
public final class LyricsRetiming {
    public static final long RETIMING_OVERLAP_THRESHOLD_MS = 5L;
    public static final long RETIMING_GAP_THRESHOLD_MS = 1L;
    public static final long RETIMING_MAX_EXTENSION_MS = 1300L;

    public static final class RetimedEntry {
        public final LyricsLine line;
        public final long startMs;
        public final long actualEndTimeMs;
        public final long adjustedEndTimeMs;

        public RetimedEntry(LyricsLine line, long startMs, long actualEndTimeMs, long adjustedEndTimeMs) {
            this.line = line;
            this.startMs = startMs;
            this.actualEndTimeMs = actualEndTimeMs;
            this.adjustedEndTimeMs = adjustedEndTimeMs;
        }
    }

    private LyricsRetiming() {
    }

    public static long[] buildAdjustedEndMaxByIndex(@Nullable List<RetimedEntry> retimedLyrics) {
        if (retimedLyrics == null || retimedLyrics.isEmpty()) {
            return new long[0];
        }
        final int size = retimedLyrics.size();
        long[] maxEnd = new long[size];
        long currentMax = Long.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            currentMax = Math.max(currentMax, retimedLyrics.get(i).adjustedEndTimeMs);
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

    @NonNull
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
            RetimedEntry retimed = retimedLyrics.get(index);
            if (positionMs <= retimed.adjustedEndTimeMs + endToleranceMs) {
                activeIndices.add(index);
            }
            index--;
        }

        Collections.reverse(activeIndices);
        return activeIndices;
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
        if (primaryIndex != -1 && positionMs > retimedLyrics.get(primaryIndex).adjustedEndTimeMs + 10L) {
            primaryIndex = -1;
        }

        if (primaryIndex != -1 && activeIndices != null && !activeIndices.isEmpty()) {
            int groupEnd = activeIndices.size() - 1;
            int groupStart = groupEnd;
            while (groupStart > 0 && activeIndices.get(groupStart) - activeIndices.get(groupStart - 1) == 1) {
                groupStart--;
            }

            int candidateIndex = Math.max(activeIndices.get(groupStart), activeIndices.get(groupEnd) - 2);
            boolean lastPrimaryStillActive = activeIndices.contains(lastActiveIndex);
            primaryIndex = (candidateIndex < lastActiveIndex && lastPrimaryStillActive)
                    ? lastActiveIndex : candidateIndex;
        } else {
            long firstStart = retimedLyrics.get(0).startMs;
            if (positionMs < firstStart) {
                primaryIndex = 0;
            } else {
                int lastStarted = findLastStartedLineIndex(retimedLyrics, positionMs);
                if (lastStarted < lastActiveIndex && lastActiveIndex < retimedLyrics.size()) {
                    primaryIndex = lastActiveIndex;
                } else {
                    primaryIndex = Math.max(0, Math.min(retimedLyrics.size() - 1, lastStarted));
                }
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

    @NonNull
    public static List<RetimedEntry> retimeLyrics(
            @Nullable List<LyricsLine> lyrics,
            @Nullable Set<Integer> gapAfterIndex) {
        if (lyrics == null || lyrics.isEmpty()) {
            return Collections.emptyList();
        }

        final int len = lyrics.size();
        long[] originalEndMs = new long[len];
        long[] newEndMs = new long[len];
        long[] startMs = new long[len];

        for (int i = 0; i < len; i++) {
            LyricsLine e = lyrics.get(i);
            startMs[i] = e.startTimeMs();
            long end = e.endTimeMs();
            if (end == LyricsLine.NO_TIME && !e.words().isEmpty()) {
                end = e.words().get(e.words().size() - 1).endMs();
            }
            if (end == LyricsLine.NO_TIME) {
                end = (i + 1 < len) ? lyrics.get(i + 1).startTimeMs() : startMs[i] + 4000L;
            }
            originalEndMs[i] = end;
            newEndMs[i] = end;
        }

        int i = 0;
        while (i < len) {
            int clusterEnd = i;
            long maxEndInRange = originalEndMs[i];

            while (clusterEnd < len - 1) {
                LyricsLine next = lyrics.get(clusterEnd + 1);
                long nextStart = next.startTimeMs();
                long overlap = maxEndInRange - nextStart;
                if (overlap > RETIMING_OVERLAP_THRESHOLD_MS) {
                    clusterEnd++;
                    maxEndInRange = Math.max(maxEndInRange, originalEndMs[clusterEnd]);
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
            }

            for (int j = i; j <= clusterEnd; j++) {
                Long cutoff = null;
                for (int k = j + 1; k <= clusterEnd; k++) {
                    boolean jClearsK = (originalEndMs[j] - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    boolean chainBrokenAtK = (originalEndMs[k - 1] - startMs[k] <= RETIMING_OVERLAP_THRESHOLD_MS);
                    if (jClearsK || chainBrokenAtK) {
                        cutoff = startMs[k];
                        break;
                    }
                }
                newEndMs[j] = (cutoff != null) ? cutoff : clusterFinalEnd;
            }

            i = clusterEnd + 1;
        }

        List<RetimedEntry> result = new ArrayList<>(len);
        for (int idx = 0; idx < len; idx++) {
            long adjEnd = (Math.abs(newEndMs[idx] - originalEndMs[idx]) > RETIMING_GAP_THRESHOLD_MS)
                    ? newEndMs[idx] : originalEndMs[idx];
            result.add(new RetimedEntry(lyrics.get(idx), startMs[idx], originalEndMs[idx], adjEnd));
        }
        return result;
    }
}
