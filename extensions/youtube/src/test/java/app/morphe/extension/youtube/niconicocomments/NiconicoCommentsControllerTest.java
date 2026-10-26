package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NiconicoCommentsControllerTest {
    @Test
    public void interpolatesAtVerySlowPlaybackRate() {
        assertEquals(
                10_100,
                NiconicoCommentsController.interpolateVideoTime(10_000, 2_000, 0.05)
        );
    }

    @Test
    public void interpolatesAtEightTimesPlaybackRate() {
        assertEquals(
                26_000,
                NiconicoCommentsController.interpolateVideoTime(10_000, 2_000, 8.0)
        );
    }

    @Test
    public void preservesFractionalPlaybackRates() {
        assertEquals(
                12_700,
                NiconicoCommentsController.interpolateVideoTime(10_000, 2_000, 1.35)
        );
    }

    @Test
    public void rebasingBeforeRateChangePreservesCurrentPosition() {
        long positionAtRateChange =
                NiconicoCommentsController.interpolateVideoTime(10_000, 2_000, 1.0);

        assertEquals(
                positionAtRateChange,
                NiconicoCommentsController.interpolateVideoTime(positionAtRateChange, 0, 8.0)
        );
        assertEquals(
                20_000,
                NiconicoCommentsController.interpolateVideoTime(positionAtRateChange, 1_000, 8.0)
        );
    }

    @Test
    public void doesNotClampLargeValidPlaybackRates() {
        assertEquals(32.5, NiconicoCommentsController.sanitizePlaybackRate(32.5), 0.0);
    }

    @Test
    public void replacesInvalidPlaybackRatesWithNormalSpeed() {
        assertEquals(1.0, NiconicoCommentsController.sanitizePlaybackRate(0.0), 0.0);
        assertEquals(1.0, NiconicoCommentsController.sanitizePlaybackRate(-1.0), 0.0);
        assertEquals(1.0, NiconicoCommentsController.sanitizePlaybackRate(Double.NaN), 0.0);
        assertEquals(
                1.0,
                NiconicoCommentsController.sanitizePlaybackRate(Double.POSITIVE_INFINITY),
                0.0
        );
    }
}
