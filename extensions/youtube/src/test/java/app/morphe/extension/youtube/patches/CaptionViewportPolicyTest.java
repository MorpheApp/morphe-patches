package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

public class CaptionViewportPolicyTest {
    private static final String A = "videoA00001", B = "videoB00002";

    @Test public void scalesEverySliderStepRelativeToNativeFont() {
        for (int percent = 30; percent <= 150; percent += 5) {
            assertEquals(80f * percent / 100f,
                    CaptionViewportPolicy.scaleFontSize(80f, percent), .001f);
        }
        assertEquals(72f, CaptionViewportPolicy.scaleFontSize(80f, 90), .001f);
        assertEquals(80f, CaptionViewportPolicy.scaleFontSize(80f, 100), .001f);
    }

    @Test public void importedValuesCannotExceedSliderBounds() {
        assertEquals(24f, CaptionViewportPolicy.scaleFontSize(80f, Integer.MIN_VALUE), .001f);
        assertEquals(120f, CaptionViewportPolicy.scaleFontSize(80f, Integer.MAX_VALUE), .001f);
    }
    @Test
    public void fitsRealContentHeightOnFoldFullscreen() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        p.record(A, 1920, 1080);
        assertEquals(1229, p.height(2184, 1968, true, false));
        p.record(A, 1440, 1080);
        assertEquals(1638, p.height(2184, 1968, true, false));
        p.record(A, 1080, 1080);
        assertEquals(1968, p.height(2184, 1968, true, false));
        p.record(A, 1080, 1920);
        assertEquals(1968, p.height(2184, 1968, true, false));
    }

    @Test
    public void preservesOrdinaryPortraitInlineAndUnknownGeometry() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        assertEquals(1968, p.height(2184, 1968, true, false));
        p.record(A, 1920, 1080);
        assertEquals(608, p.height(1080, 608, true, false));
        assertEquals(1080, p.height(2520, 1080, true, false));
        assertEquals(2184, p.height(1968, 2184, true, false));
        assertEquals(1968, p.height(2184, 1968, false, false));
        assertEquals(0, p.height(2184, 0, true, false));
    }

    @Test
    public void prefetchedFormatsNeverReplaceSelectedVideo() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        p.record(A, 1920, 1080);
        p.record(B, 1440, 1080);
        assertEquals(1229, p.height(2184, 1968, true, false));
        p.select(B);
        assertEquals(1638, p.height(2184, 1968, true, false));
        p.select("unknown0001");
        assertEquals(1968, p.height(2184, 1968, true, false));
    }

    @Test
    public void coldStartSupportsFormatsBeforeAndAfterSelection() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.record(A, 1920, 1080);
        p.select(A);
        assertEquals(1229, p.height(2184, 1968, true, false));
        p.select(B);
        assertEquals(1968, p.height(2184, 1968, true, false));
        p.record(B, 1920, 1080);
        assertEquals(1229, p.height(2184, 1968, true, false));
    }

    @Test
    public void malformedMetadataDoesNotOverrideValidSnapshot() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        p.record(A, 1920, 1080);
        p.record(A, 0, 1080);
        p.record(A, -1, 1080);
        p.record(A, 100000, 1);
        assertEquals(1229, p.height(2184, 1968, true, false));
        p.select(null);
        assertEquals(1968, p.height(2184, 1968, true, false));
    }

    @Test
    public void boundedPrefetchCacheRetainsSelectedSnapshot() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        p.record(A, 1920, 1080);
        for (int i = 0; i < 100; i++) p.record(String.format("%011d", i), 1440, 1080);
        assertEquals(1229, p.height(2184, 1968, true, false));
        p.select(String.format("%011d", 68));
        assertEquals(1968, p.height(2184, 1968, true, false));
        p.select(String.format("%011d", 69));
        assertEquals(1638, p.height(2184, 1968, true, false));
        p.select(String.format("%011d", 99));
        assertEquals(1638, p.height(2184, 1968, true, false));
    }

    @Test
    public void landscapeTransitionFitsEveryAnimatedFrameWithoutThresholdJump() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        p.record(A, 1920, 1080);
        // Physical Fold exit trace, in reverse for entry as well.
        int[][] frames = {
            {2184, 1968},
            {2089, 1807},
            {1974, 1613},
            {1849, 1403},
            {1810, 1336},
            {1722, 1187},
            {1599, 979},
            {1531, 864},
            {1528, 859}
        };
        for (int[] f : frames) {
            int expected = Math.min(f[1], Math.round(f[0] * 9f / 16f));
            assertEquals(
                    "animated frame " + f[0] + "x" + f[1],
                    expected,
                    p.height(f[0], f[1], true, true));
        }
        // Target orientation is landscape even if an intermediate viewport is portrait.
        assertEquals(1107, p.height(1968, 2184, true, true));
        assertEquals(2184, p.height(1968, 2184, true, false));
        assertEquals(1968, p.height(2184, 1968, false, true));
    }

    @Test
    public void transitionKeepsUnknownGeometryAndNonWideContentNative() {
        CaptionViewportPolicy p = new CaptionViewportPolicy();
        p.select(A);
        assertEquals(1968, p.height(2184, 1968, true, true));
        p.record(A, 1080, 1920);
        assertEquals(1968, p.height(2184, 1968, true, true));
        p.record(A, 1440, 1080);
        assertEquals(1638, p.height(2184, 1968, true, true));
    }
}
