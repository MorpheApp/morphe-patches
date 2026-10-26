/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.youtube.settings.Settings;

public class NiconicoCommentsControllerTest {
    @Test
    public void dropsInactiveLiveBatchesButPreservesReplayLookahead() {
        assertTrue(NiconicoCommentsController.shouldAcceptComments(250, true, true));
        assertFalse(NiconicoCommentsController.shouldAcceptComments(250, false, true));
        assertFalse(NiconicoCommentsController.shouldAcceptComments(250, true, false));
        assertFalse(NiconicoCommentsController.shouldAcceptComments(250, false, false));
        assertTrue(NiconicoCommentsController.shouldAcceptComments(0, false, true));
        assertTrue(NiconicoCommentsController.shouldAcceptComments(0, true, false));
        assertTrue(NiconicoCommentsController.shouldAcceptComments(0, false, false));
    }

    @Test
    public void removesCommentsLongerThanTheMaxLength() {
        NiconicoComment shortComment = new NiconicoComment("short", "12345", false, 0, 0);
        NiconicoComment longComment = new NiconicoComment("long", "123456", false, 0, 0);
        List<NiconicoComment> comments = List.of(shortComment, longComment);

        assertEquals(List.of(shortComment), NiconicoCommentsController.removeLongComments(comments, 5));
        assertEquals(comments, NiconicoCommentsController.removeLongComments(
                comments, Settings.NICONICO_COMMENTS_MAX_LENGTH_NO_LIMIT));
    }

    @Test
    public void countsEachEmojiAsOneCharacter() {
        NiconicoComment comment = new NiconicoComment("emoji", "hi:face-blue-smiling:", List.of(
                NiconicoComment.Segment.text("hi"),
                NiconicoComment.Segment.emoji(":face-blue-smiling:", "https://example.com/emoji.png")
        ), false, 0, 0);

        assertEquals(3, comment.length());
    }

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
