/* Copyright 2026 Morphe. See the included NOTICE file for GPLv3 Section 7 terms that apply to this code. */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Unit tests for the pure geometry methods of {@link SkipSegments}: effectiveDurationMs, remapTimestamp, contentMs and withSegment. These are static and stateless, so no Android context is needed. */
public class SkipSegmentsTest {

    // ---- helpers ----

    private static SkipSegments.Seg seg(long start, long end) {
        return new SkipSegments.Seg(start, end, "sponsor", 0);
    }

    // ---- effectiveDurationMs ----

    @Test
    public void effectiveDurationMs_noSegmentsReturnsOriginal() {
        assertEquals(10_000L, SkipSegments.effectiveDurationMs(10_000L, List.of()));
    }

    @Test
    public void effectiveDurationMs_nullSegmentsReturnsOriginal() {
        assertEquals(10_000L, SkipSegments.effectiveDurationMs(10_000L, null));
    }

    @Test
    public void effectiveDurationMs_singleSegmentReducesDuration() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 3_000));
        assertEquals(8_000L, SkipSegments.effectiveDurationMs(10_000L, segs));
    }

    @Test
    public void effectiveDurationMs_multipleSegmentsComputeUnion() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 2_000), seg(3_000, 5_000));
        assertEquals(6_000L, SkipSegments.effectiveDurationMs(10_000L, segs));
    }

    @Test
    public void effectiveDurationMs_zeroDurationSegmentNoEffect() {
        List<SkipSegments.Seg> segs = List.of(seg(5_000, 5_000));
        assertEquals(10_000L, SkipSegments.effectiveDurationMs(10_000L, segs));
    }

    @Test
    public void effectiveDurationMs_negativeDurationReturnsOriginal() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 2_000));
        assertEquals(-5_000L, SkipSegments.effectiveDurationMs(-5_000L, segs));
    }

    @Test
    public void effectiveDurationMs_segmentExceedingDurationClampsToOriginal() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 10_000));
        assertEquals(10_000L, SkipSegments.effectiveDurationMs(10_000L, segs));
    }

    // ---- remapTimestamp ----

    @Test
    public void remapTimestamp_noSegmentsReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.remapTimestamp(5_000L, List.of()));
    }

    @Test
    public void remapTimestamp_nullSegmentsReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.remapTimestamp(5_000L, null));
    }

    @Test
    public void remapTimestamp_negativeContentReturnsOriginal() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 2_000));
        assertEquals(-1L, SkipSegments.remapTimestamp(-1L, segs));
    }

    @Test
    public void remapTimestamp_singleSegmentAddsLength() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 3_000));
        assertEquals(4_000L, SkipSegments.remapTimestamp(2_000L, segs));
    }

    @Test
    public void remapTimestamp_beforeSegmentStartNoChange() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 3_000));
        assertEquals(500L, SkipSegments.remapTimestamp(500L, segs));
    }

    @Test
    public void remapTimestamp_multipleSegmentsAddsAll() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 1_000), seg(1_500, 2_500));
        assertEquals(4_000L, SkipSegments.remapTimestamp(2_000L, segs));
    }

    // ---- contentMs ----

    @Test
    public void contentMs_noSegmentsReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.contentMs(5_000L, List.of()));
    }

    @Test
    public void contentMs_nullSegmentsReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.contentMs(5_000L, null));
    }

    @Test
    public void contentMs_negativeVideoMsReturnsOriginal() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 2_000));
        assertEquals(-1L, SkipSegments.contentMs(-1L, segs));
    }

    @Test
    public void contentMs_withinSegmentClampsToStart() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 3_000));
        assertEquals(1_000L, SkipSegments.contentMs(2_000L, segs));
    }

    @Test
    public void contentMs_afterSegmentSubtractsSkipped() {
        List<SkipSegments.Seg> segs = List.of(seg(1_000, 3_000));
        assertEquals(2_000L, SkipSegments.contentMs(4_000L, segs));
    }

    @Test
    public void contentMs_multipleSegmentsInverseOfRemap() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 1_000), seg(1_500, 2_500));
        for (long content : new long[]{0, 500, 1_000, 1_200, 2_000, 3_000, 5_000}) {
            long video = SkipSegments.remapTimestamp(content, segs);
            assertEquals("Round-trip failed for content=" + content,
                    content, SkipSegments.contentMs(video, segs));
        }
    }

    // ---- withSegment ----

    @Test
    public void withSegment_emptyBaseReturnsExtra() {
        SkipSegments.Seg extra = seg(1_000, 2_000);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(), extra);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1_000L, result.get(0).startMs());
        assertEquals(2_000L, result.get(0).endMs());
    }

    @Test
    public void withSegment_nullBaseReturnsExtra() {
        SkipSegments.Seg extra = seg(1_000, 2_000);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(null, extra);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1_000L, result.get(0).startMs());
    }

    @Test
    public void withSegment_overlappingMergesIntoOne() {
        SkipSegments.Seg existing = seg(0, 1_000);
        SkipSegments.Seg extra = seg(500, 1_500);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(existing), extra);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(0L, result.get(0).startMs());
        assertEquals(1_500L, result.get(0).endMs());
    }

    @Test
    public void withSegment_nonOverlappingAddsSeparate() {
        SkipSegments.Seg existing = seg(0, 1_000);
        SkipSegments.Seg extra = seg(2_000, 3_000);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(existing), extra);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0).startMs());
        assertEquals(2_000L, result.get(1).startMs());
    }

    @Test
    public void withSegment_resultSortedByStartMs() {
        SkipSegments.Seg later = seg(2_000, 3_000);
        SkipSegments.Seg earlier = seg(0, 1_000);
        SkipSegments.Seg extra = seg(500, 1_500);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(
                Arrays.asList(later, earlier), extra);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.get(0).startMs() < result.get(1).startMs());
        assertEquals(0L, result.get(0).startMs());
        assertEquals(2_000L, result.get(1).startMs());
    }

    @Test
    public void withSegment_resultIsUnmodifiable() {
        SkipSegments.Seg extra = seg(0, 1_000);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(), extra);
        try {
            result.add(seg(2_000, 3_000));
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void withSegment_mergePreservesReportedDuration() {
        SkipSegments.Seg withReported = new SkipSegments.Seg(0, 1_000, "sponsor", 5_000);
        SkipSegments.Seg withoutReported = new SkipSegments.Seg(500, 1_500, "sponsor", 0);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(withReported), withoutReported);
        assertEquals(1, result.size());
        assertEquals(5_000L, result.get(0).reportedDurationMs());
    }

    @Test
    public void withSegment_mergeTakesReportedDurationFromNextWhenCurrentIsZero() {
        SkipSegments.Seg withoutReported = new SkipSegments.Seg(0, 1_000, "sponsor", 0);
        SkipSegments.Seg withReported = new SkipSegments.Seg(500, 1_500, "sponsor", 7_000);
        List<SkipSegments.Seg> result = SkipSegments.withSegment(List.of(withoutReported), withReported);
        assertEquals(1, result.size());
        assertEquals(7_000L, result.get(0).reportedDurationMs());
    }

    @Test
    public void effectiveDurationMs_overlappingSegmentsMergedFirstThenUnion() {
        SkipSegments.Seg first = seg(0, 5_000);
        SkipSegments.Seg second = seg(3_000, 8_000);
        List<SkipSegments.Seg> merged = SkipSegments.withSegment(List.of(first), second);
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0).startMs());
        assertEquals(8_000L, merged.get(0).endMs());
        assertEquals(2_000L, SkipSegments.effectiveDurationMs(10_000L, merged));
    }

    @Test
    public void remapTimestamp_atSegmentBoundaryAddsLength() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 1_000));
        assertEquals(2_000L, SkipSegments.remapTimestamp(1_000L, segs));
    }

    @Test
    public void contentMs_atSegmentBoundaryReturnsContentBeforeSegment() {
        List<SkipSegments.Seg> segs = List.of(seg(0, 1_000));
        assertEquals(0L, SkipSegments.contentMs(1_000L, segs));
    }

    @Test
    public void contentMs_emptyListReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.contentMs(5_000L, Collections.emptyList()));
    }

    @Test
    public void effectiveDurationMs_emptyListReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.effectiveDurationMs(5_000L, Collections.emptyList()));
    }

    @Test
    public void remapTimestamp_emptyListReturnsOriginal() {
        assertEquals(5_000L, SkipSegments.remapTimestamp(5_000L, Collections.emptyList()));
    }

    @Test
    public void withSegment_neverReturnsNull() {
        assertNotNull(SkipSegments.withSegment(null, seg(0, 1_000)));
        assertNotNull(SkipSegments.withSegment(List.of(), seg(0, 1_000)));
        assertNotNull(SkipSegments.withSegment(List.of(seg(0, 1_000)), seg(2_000, 3_000)));
    }
}
