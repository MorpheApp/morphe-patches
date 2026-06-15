package app.morphe.extension.music.sponsorblock;

import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;

import app.morphe.extension.music.settings.MusicSponsorBlockSettings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.music.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.music.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.music.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.music.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Handles fetching and skipping SponsorBlock segments in YouTube Music.
 * All methods except {@link #setVideoId} must be called on the main thread.
 */
@SuppressWarnings("unused")
public class SegmentPlaybackController {

    @Nullable
    private static String currentVideoId;
    @Nullable
    private static SponsorSegment[] segments;

    @Nullable
    private static SponsorSegment segmentCurrentlyPlaying;
    @Nullable
    private static SponsorSegment scheduledUpcomingSegment;
    @Nullable
    private static SponsorSegment scheduledHideSegment;

    private static long skipSegmentButtonEndTime;

    // Seekbar bounds (pixels), captured from the seekbar's own Rect each draw.
    private static int sbAbsoluteLeft;
    private static int sbAbsoluteRight;
    private static int sbAbsoluteTop;
    private static int sbAbsoluteBottom;
    private static int sbThickness = 7;

    @Nullable
    private static SponsorSegment lastSegmentSkipped;
    private static long lastSegmentSkippedTime;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private static void clearData() {
        MusicSponsorBlockSettings.initialize();
        // Reload colors each track so seekbar color-picker changes apply without an app restart.
        SegmentCategory.updateEnabledCategories();
        currentVideoId = null;
        segments = null;
        segmentCurrentlyPlaying = null;
        scheduledUpcomingSegment = null;
        scheduledHideSegment = null;
        skipSegmentButtonEndTime = 0;
    }

    // -------------------------------------------------------------------------
    // Injection points
    // -------------------------------------------------------------------------

    /** Injection point. Called off the main thread when the video ID changes. */
    public static void setVideoId(@NonNull String videoId) {
        try {
            if (Objects.equals(currentVideoId, videoId)) return;
            clearData();
            if (!MusicSponsorBlockSettings.SB_ENABLED.get()) return;
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "SponsorBlock: network not connected");
                return;
            }
            currentVideoId = videoId;
            Logger.printDebug(() -> "SponsorBlock: fetching segments for " + videoId);
            Utils.runOnBackgroundThread(() -> {
                try {
                    executeDownloadSegments(videoId);
                } catch (Exception ex) {
                    Logger.printException(() -> "Failed to fetch segments", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoId failure", ex);
        }
    }

    /**
     * Injection point. Called on the main thread ~every 1000ms.
     * Checks whether the current position is inside a segment and skips if needed.
     */
    public static void setVideoTime(long millis) {
        try {
            if (!MusicSponsorBlockSettings.SB_ENABLED.get()
                    || segments == null || segments.length == 0) return;

            final float speed = VideoInformation.getPlaybackSpeed();
            final long lookAhead = (long) (speed * 1200);
            final long startLookAhead = millis + lookAhead;

            SponsorSegment upcomingFound = null;
            SponsorSegment currentFound = null;

            for (SponsorSegment seg : segments) {
                if (seg.category.getBehaviour() == CategoryBehaviour.IGNORE) continue;
                if (seg.end <= millis) continue;

                if (seg.start <= millis) {
                    if (seg.shouldAutoSkip()) {
                        skipSegment(seg);
                        return;
                    }
                    if (currentFound == null || currentFound.containsSegment(seg)) {
                        if (segmentCurrentlyPlaying == seg
                                || !seg.endIsNear(millis, 800)) {
                            currentFound = seg;
                        }
                    }
                    continue;
                }

                if (seg.start <= startLookAhead) {
                    if (seg.shouldAutoSkip() && (upcomingFound == null
                            || upcomingFound.start > seg.start)) {
                        upcomingFound = seg;
                    }
                }
            }

            segmentCurrentlyPlaying = currentFound;
            scheduledUpcomingSegment = upcomingFound;
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoTime failure", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Seekbar drawing
    // -------------------------------------------------------------------------

    /**
     * Injection point. Captures the seekbar's pixel bounds from its own Rect, passed directly
     * from {@code MusicPlaybackControlsTimeBar.draw()} (no reflection).
     */
    public static void setSponsorBarRect(@Nullable Rect rect) {
        if (rect != null) {
            sbAbsoluteLeft = rect.left;
            sbAbsoluteRight = rect.right;
            sbAbsoluteTop = rect.top;
            sbAbsoluteBottom = rect.bottom;
        }
    }

    /** Injection point. Sets the stroke thickness for the segment overlay bars. */
    public static void setSponsorBarThickness(int thickness) {
        sbThickness = Math.max(1, thickness);
    }

    /**
     * Injection point. Draws colored segment markers on the seekbar canvas. Called from
     * {@code MusicPlaybackControlsTimeBar.draw()} right after super.draw(), so it runs every frame
     * (not only while the scrubber thumb is shown).
     */
    public static void drawSponsorTimeBars(@NonNull Canvas canvas) {
        SponsorSegment[] segs = segments;
        if (segs == null || segs.length == 0) return;

        final long videoLength = VideoInformation.getVideoLength();
        if (videoLength <= 0) return;

        final float barWidth = sbAbsoluteRight - sbAbsoluteLeft;
        if (barWidth <= 0) return;

        final float centerY = (sbAbsoluteTop + sbAbsoluteBottom) / 2f;

        for (SponsorSegment seg : segs) {
            if (seg.category.getBehaviour() == CategoryBehaviour.IGNORE) continue;

            float left  = sbAbsoluteLeft + (float) seg.start / videoLength * barWidth;
            float right = sbAbsoluteLeft + (float) seg.end   / videoLength * barWidth;
            right = Math.max(left + 2, right);

            seg.category.paint.setStrokeWidth(sbThickness);
            canvas.drawLine(left, centerY, right, centerY, seg.category.paint);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void executeDownloadSegments(@NonNull String videoId) {
        SponsorSegment[] fetched = SBRequester.getSegments(videoId);
        Utils.runOnMainThread(() -> {
            if (!videoId.equals(currentVideoId)) {
                Logger.printDebug(() -> "SponsorBlock: stale segments, ignoring " + videoId);
                return;
            }
            Arrays.sort(fetched);
            segments = fetched;
            setVideoTime(VideoInformation.getVideoTime());
        });
    }

    private static void skipSegment(@NonNull SponsorSegment segment) {
        try {
            if (lastSegmentSkipped == segment) {
                final long now = System.currentTimeMillis();
                if (now - lastSegmentSkippedTime < 3000) {
                    Logger.printDebug(() -> "SponsorBlock: skipping duplicate skip for " + segment);
                    return;
                }
            }
            lastSegmentSkipped = segment;
            lastSegmentSkippedTime = System.currentTimeMillis();
            segment.didAutoSkip = true;

            Logger.printDebug(() -> "SponsorBlock: auto-skipping " + segment);

            final boolean seeked = VideoInformation.seekTo(segment.end);
            if (seeked && MusicSponsorBlockSettings.SB_TOAST_ON_SKIP.get()) {
                Utils.showToastShort(segment.getSkippedToastText());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "skipSegment failure", ex);
        }
    }
}
