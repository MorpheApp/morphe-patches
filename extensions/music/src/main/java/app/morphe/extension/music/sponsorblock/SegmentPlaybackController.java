package app.morphe.extension.music.sponsorblock;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.music.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.music.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.music.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Handles showing, scheduling, and skipping of all {@link SponsorSegment} for the current video.
 *
 * <p>Class is not thread safe. All methods must be called on the main thread unless otherwise
 * specified.
 */
@SuppressWarnings("unused")
public class SegmentPlaybackController {

    @Nullable
    private static String currentVideoId;

    @Nullable
    private static SponsorSegment[] segments;

    /** Currently playing (non-highlight) segment that the user can manually skip. */
    @Nullable
    private static SponsorSegment segmentCurrentlyPlaying;

    /**
     * Currently playing manual-skip segment that is scheduled to hide.
     * Always NULL or equal to {@link #segmentCurrentlyPlaying}.
     */
    @Nullable
    private static SponsorSegment scheduledHideSegment;

    /** Upcoming segment scheduled to either auto-skip or show the manual skip button. */
    @Nullable
    private static SponsorSegment scheduledUpcomingSegment;

    /**
     * System time (ms) when the skip button for {@link #segmentCurrentlyPlaying} should hide.
     * Zero when not inside a segment.
     */
    private static long skipSegmentButtonEndTime;

    private static int sponsorBarAbsoluteLeft;
    private static int sponsorAbsoluteBarRight;
    private static int sponsorBarThickness = 7;

    private static SponsorSegment lastSegmentSkipped;
    private static long lastSegmentSkippedTime;
    private static int toastNumberOfSegmentsSkipped;

    @Nullable
    private static SponsorSegment toastSegmentSkipped;

    private static void setSegments(@NonNull SponsorSegment[] videoSegments) {
        Arrays.sort(videoSegments);
        segments = videoSegments;
    }

    /** Clears all downloaded segment data and resets state. */
    private static void clearData() {
        SponsorBlockSettings.initialize();
        currentVideoId = null;
        segments = null;
        segmentCurrentlyPlaying = null;
        scheduledUpcomingSegment = null;
        scheduledHideSegment = null;
        skipSegmentButtonEndTime = 0;
        toastSegmentSkipped = null;
        toastNumberOfSegmentsSkipped = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Injection points — called from patched YTM bytecode
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injection point.
     * Called when the video ID changes (new video started or background play resumes).
     */
    public static void setVideoId(@NonNull String videoId) {
        try {
            if (Objects.equals(currentVideoId, videoId)) {
                return;
            }
            clearData();
            if (!Settings.SB_ENABLED.get()) {
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Network not connected, ignoring video");
                return;
            }

            currentVideoId = videoId;
            Logger.printDebug(() -> "setCurrentVideoId: " + videoId);

            Utils.runOnBackgroundThread(() -> {
                try {
                    executeDownloadSegments(videoId);
                } catch (Exception e) {
                    Logger.printException(() -> "Failed to download segments", e);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setCurrentVideoId failure", ex);
        }
    }

    /** Must be called off the main thread. */
    static void executeDownloadSegments(@NonNull String videoId) {
        Objects.requireNonNull(videoId);
        try {
            SponsorSegment[] fetched = SBRequester.getSegments(videoId);

            Utils.runOnMainThread(() -> {
                if (!videoId.equals(currentVideoId)) {
                    Logger.printDebug(() -> "Ignoring segments for prior video: " + videoId);
                    return;
                }
                setSegments(fetched);
                setVideoTime(VideoInformation.getVideoTime());
            });
        } catch (Exception ex) {
            Logger.printException(() -> "executeDownloadSegments failure", ex);
        }
    }

    /**
     * Injection point.
     * Called roughly every 1 000 ms during playback. Drives the auto-skip and
     * manual-skip-button scheduling logic.
     */
    public static void setVideoTime(long millis) {
        try {
            if (!Settings.SB_ENABLED.get() || segments == null || segments.length == 0) {
                return;
            }
            Logger.printDebug(() -> "setVideoTime: " + millis);

            final float playbackSpeed = VideoInformation.getPlaybackSpeed();
            final long speedAdjustedTimeThreshold = (long) (playbackSpeed * 1200);
            final long startTimerLookAheadThreshold = millis + speedAdjustedTimeThreshold;

            SponsorSegment foundSegmentCurrentlyPlaying = null;
            SponsorSegment foundUpcomingSegment = null;

            for (final SponsorSegment segment : segments) {
                if (segment.category.behaviour == CategoryBehaviour.IGNORE) continue;
                if (segment.end <= millis) continue;

                if (segment.start <= millis) {
                    if (segment.shouldAutoSkip()) {
                        skipSegment(segment);
                        return;
                    }
                    if (foundSegmentCurrentlyPlaying == null
                            || foundSegmentCurrentlyPlaying.containsSegment(segment)) {
                        final long minMillisRemainingThreshold = 800;
                        if (segmentCurrentlyPlaying == segment
                                || !segment.endIsNear(millis, minMillisRemainingThreshold)) {
                            foundSegmentCurrentlyPlaying = segment;
                        } else {
                            Logger.printDebug(() -> "Ignoring segment that ends very soon: " + segment);
                        }
                    }
                    continue;
                }

                if (startTimerLookAheadThreshold < segment.start) break;
                if (segment.shouldAutoSkip()) {
                    foundUpcomingSegment = segment;
                    break;
                }
                if ((foundSegmentCurrentlyPlaying == null
                        || foundSegmentCurrentlyPlaying.containsSegment(segment))
                        && (foundUpcomingSegment == null
                        || foundUpcomingSegment.containsSegment(segment))) {
                    final long minTimeBetweenStartEnd = 1000;
                    if (foundSegmentCurrentlyPlaying == null
                            || !foundSegmentCurrentlyPlaying.endIsNear(
                                    segment.start, minTimeBetweenStartEnd)) {
                        foundUpcomingSegment = segment;
                    } else {
                        Logger.printDebug(
                                () -> "Not scheduling segment (start near end of current): " + segment);
                    }
                }
            }

            if (segmentCurrentlyPlaying != foundSegmentCurrentlyPlaying) {
                setSegmentCurrentlyPlaying(foundSegmentCurrentlyPlaying);
            } else if (foundSegmentCurrentlyPlaying != null
                    && skipSegmentButtonEndTime != 0
                    && skipSegmentButtonEndTime <= System.currentTimeMillis()) {
                Logger.printDebug(() -> "Auto hiding skip button: " + segmentCurrentlyPlaying);
                skipSegmentButtonEndTime = 0;
            }

            final SponsorSegment segmentToHide =
                    (foundSegmentCurrentlyPlaying != null
                            && foundSegmentCurrentlyPlaying.endIsNear(millis, speedAdjustedTimeThreshold))
                            ? foundSegmentCurrentlyPlaying : null;

            if (scheduledHideSegment != segmentToHide) {
                if (segmentToHide == null) {
                    Logger.printDebug(() -> "Clearing scheduled hide: " + scheduledHideSegment);
                    scheduledHideSegment = null;
                } else {
                    scheduledHideSegment = segmentToHide;
                    Logger.printDebug(
                            () -> "Scheduling hide: " + segmentToHide + " speed: " + playbackSpeed);
                    final long delay = (long) ((segmentToHide.end - millis) / playbackSpeed);
                    Utils.runOnMainThreadDelayed(() -> {
                        if (scheduledHideSegment != segmentToHide) {
                            Logger.printDebug(() -> "Ignoring old scheduled hide: " + segmentToHide);
                            return;
                        }
                        scheduledHideSegment = null;
                        final long videoTime = VideoInformation.getVideoTime();
                        if (!segmentToHide.endIsNear(videoTime, speedAdjustedTimeThreshold)) {
                            Logger.printDebug(() -> "Ignoring outdated scheduled hide: " + segmentToHide);
                            return;
                        }
                        Logger.printDebug(() -> "Running scheduled hide: " + segmentToHide);
                        setSegmentCurrentlyPlaying(null);
                        setVideoTime(segmentToHide.end);
                    }, delay);
                }
            }

            if (scheduledUpcomingSegment != foundUpcomingSegment) {
                if (foundUpcomingSegment == null) {
                    Logger.printDebug(() -> "Clearing scheduled segment: " + scheduledUpcomingSegment);
                    scheduledUpcomingSegment = null;
                } else {
                    scheduledUpcomingSegment = foundUpcomingSegment;
                    final SponsorSegment segmentToSkip = foundUpcomingSegment;
                    Logger.printDebug(
                            () -> "Scheduling: " + segmentToSkip + " speed: " + playbackSpeed);
                    final long delay = (long) ((segmentToSkip.start - millis) / playbackSpeed);
                    Utils.runOnMainThreadDelayed(() -> {
                        if (scheduledUpcomingSegment != segmentToSkip) {
                            Logger.printDebug(() -> "Ignoring old scheduled segment: " + segmentToSkip);
                            return;
                        }
                        scheduledUpcomingSegment = null;
                        final long videoTime = VideoInformation.getVideoTime();
                        if (!segmentToSkip.startIsNear(videoTime, speedAdjustedTimeThreshold)) {
                            Logger.printDebug(
                                    () -> "Ignoring outdated scheduled segment: " + segmentToSkip);
                            return;
                        }
                        if (segmentToSkip.shouldAutoSkip()) {
                            Logger.printDebug(() -> "Running scheduled skip: " + segmentToSkip);
                            skipSegment(segmentToSkip);
                        } else {
                            Logger.printDebug(() -> "Running scheduled show: " + segmentToSkip);
                            setSegmentCurrentlyPlaying(segmentToSkip);
                        }
                    }, delay);
                }
            }

        } catch (Exception e) {
            Logger.printException(() -> "setVideoTime failure", e);
        }
    }

    private static void setSegmentCurrentlyPlaying(@Nullable SponsorSegment segment) {
        if (segment == null) {
            if (segmentCurrentlyPlaying != null)
                Logger.printDebug(() -> "Hiding segment: " + segmentCurrentlyPlaying);
            segmentCurrentlyPlaying = null;
            skipSegmentButtonEndTime = 0;
            return;
        }
        segmentCurrentlyPlaying = segment;
        skipSegmentButtonEndTime = 0;
        Logger.printDebug(() -> "Showing segment: " + segment);
    }

    private static void skipSegment(@NonNull SponsorSegment segmentToSkip) {
        try {
            final long now = System.currentTimeMillis();
            final long minMillisBetweenSkips = 500;
            if (lastSegmentSkipped == segmentToSkip
                    && now - lastSegmentSkippedTime < minMillisBetweenSkips) {
                Logger.printDebug(() -> "Ignoring duplicate skip: " + segmentToSkip);
                return;
            }

            Logger.printDebug(() -> "Skipping segment: " + segmentToSkip);
            lastSegmentSkipped = segmentToSkip;
            lastSegmentSkippedTime = now;
            setSegmentCurrentlyPlaying(null);
            scheduledHideSegment = null;
            scheduledUpcomingSegment = null;

            final boolean seekSuccessful = VideoInformation.seekTo(segmentToSkip.end);
            if (!seekSuccessful) {
                Logger.printDebug(() -> "Could not skip (seek unsuccessful): " + segmentToSkip);
                return;
            }

            final boolean showToast = Settings.SB_TOAST_ON_SKIP.get();
            for (final SponsorSegment other : Objects.requireNonNull(segments)) {
                if (segmentToSkip.end < other.start) break;
                if (other == segmentToSkip || segmentToSkip.containsSegment(other)) {
                    other.didAutoSkipped = true;
                    if (showToast) showSkippedSegmentToast(other);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "skipSegment failure", ex);
        }
    }

    private static void showSkippedSegmentToast(@NonNull SponsorSegment segment) {
        Utils.verifyOnMainThread();
        toastNumberOfSegmentsSkipped++;
        if (toastNumberOfSegmentsSkipped > 1) return;
        toastSegmentSkipped = segment;

        final long toastDelay = 250;
        Utils.runOnMainThreadDelayed(() -> {
            try {
                if (toastSegmentSkipped == null) {
                    Logger.printDebug(() -> "Ignoring old toast");
                    return;
                }
                Utils.showToastShort(toastNumberOfSegmentsSkipped == 1
                        ? toastSegmentSkipped.getSkippedToastText()
                        : str("revanced_sb_skipped_multiple_segments"));
            } catch (Exception ex) {
                Logger.printException(() -> "showSkippedSegmentToast failure", ex);
            } finally {
                toastNumberOfSegmentsSkipped = 0;
                toastSegmentSkipped = null;
            }
        }, toastDelay);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Seekbar drawing injection points
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injection point.
     * Called from the patched seekbar/timebar constructor or onDraw.
     * Reads the Rect field by reflection so we know exactly where the bar is drawn.
     */
    public static void setSponsorBarRect(final Object self, final String fieldName) {
        if (!Settings.SB_ENABLED.get()) return;
        try {
            Field field = self.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Rect rect = (Rect) Objects.requireNonNull(field.get(self));
            final int left  = rect.left;
            final int right = rect.right;
            if (sponsorBarAbsoluteLeft  != left)  sponsorBarAbsoluteLeft  = left;
            if (sponsorAbsoluteBarRight != right) sponsorAbsoluteBarRight = right;
        } catch (Exception ex) {
            Logger.printException(() -> "setSponsorBarRect failure", ex);
        }
    }

    /**
     * Injection point.
     * Stores the seekbar thickness so segment bars match the visual height of the seekbar.
     */
    public static void setSponsorBarThickness(int thickness) {
        if (Settings.SB_ENABLED.get() && sponsorBarThickness != thickness) {
            sponsorBarThickness = (int) Math.round(thickness * 1.2);
        }
    }

    /**
     * Injection point.
     * Called just before the scrubber thumb circle is drawn on the seekbar / timebar.
     * Draws colored rectangles representing each SponsorBlock segment.
     *
     * @param canvas The canvas passed to onDraw.
     * @param posY   The Y centre of the seekbar track (used for top/bottom of bars).
     */
    public static void drawSponsorTimeBars(final Canvas canvas, final float posY) {
        try {
            if (!Settings.SB_ENABLED.get() || segments == null) return;
            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength <= 0) return;

            final int thicknessDiv2 = sponsorBarThickness / 2;
            final float top    = posY - (sponsorBarThickness - thicknessDiv2);
            final float bottom = posY + thicknessDiv2;
            final float pixelsPerMs =
                    (1f / videoLength) * (sponsorAbsoluteBarRight - sponsorBarAbsoluteLeft);
            final float leftPadding = sponsorBarAbsoluteLeft;

            for (SponsorSegment segment : segments) {
                final float left  = leftPadding + segment.start * pixelsPerMs;
                final float right = leftPadding + segment.end   * pixelsPerMs;
                canvas.drawRect(left, top, right, bottom, segment.category.paint);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "drawSponsorTimeBars failure", ex);
        }
    }
}
