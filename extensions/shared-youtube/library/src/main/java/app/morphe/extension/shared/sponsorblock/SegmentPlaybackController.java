/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.SKIP_AUTOMATICALLY;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.text.TextUtils;
import android.util.Range;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.ChannelWhitelistAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.Configuration;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.PlayerStateAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.SettingsAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.UiBridge;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.VideoInformationAdapter;
import app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;
import app.morphe.extension.shared.ui.Dim;

/**
 * Handles showing, scheduling, and skipping of all {@link SponsorSegment} for the current video.
 * <p>
 * Class is not thread safe. All methods must be called on the main thread unless otherwise specified.
 */
@SuppressLint("NewApi")
public class SegmentPlaybackController {

    /**
     * Enum for configurable durations (1 to 10 seconds) for skip button and toast display.
     */
    public enum SponsorBlockDuration {
        ONE_SECOND(1),
        TWO_SECONDS(2),
        THREE_SECONDS(3),
        FOUR_SECONDS(4),
        FIVE_SECONDS(5),
        SIX_SECONDS(6),
        SEVEN_SECONDS(7),
        EIGHT_SECONDS(8),
        NINE_SECONDS(9),
        TEN_SECONDS(10);

        /**
         * Duration, minus 200ms to adjust for exclusive end time checking in scheduled show/hides.
         */
        public final long adjustedDuration;

        SponsorBlockDuration(int seconds) {
            adjustedDuration = seconds * 1000L - 200;
        }
    }

    /**
     * Highlight segments have zero length as they are a point in time.
     * Draw them on screen using a fixed width bar.
     */
    private static final int HIGHLIGHT_SEGMENT_DRAW_BAR_WIDTH = Dim.dp7;

    @Nullable
    private static String currentVideoId;
    @Nullable
    private static SponsorSegment[] segments;

    /**
     * Highlight segment, if one exists and the skip behavior is not set to {@link CategoryBehaviour#SHOW_IN_SEEKBAR}.
     */
    @Nullable
    private static SponsorSegment highlightSegment;

    /**
     * Because loading can take time, show the skip to highlight for a few seconds after the segments load.
     * This is the system time (in milliseconds) to no longer show the initial display skip to highlight.
     * Value is zero if no highlight segment exists, or if the system time to show the highlight has passed.
     */
    private static long highlightSegmentInitialShowEndTime;

    /**
     * Currently playing (non-highlight) segment that user can manually skip.
     */
    @Nullable
    private static SponsorSegment segmentCurrentlyPlaying;
    /**
     * Currently playing manual skip segment that is scheduled to hide.
     * This is always NULL or equal to {@link #segmentCurrentlyPlaying}.
     */
    @Nullable
    private static SponsorSegment scheduledHideSegment;
    /**
     * Upcoming segment that is scheduled to either autoskip or show the manual skip button.
     */
    @Nullable
    private static SponsorSegment scheduledUpcomingSegment;

    /**
     * Used to prevent re-showing a previously hidden skip button when exiting an embedded segment.
     * Only used when {@link SettingsAdapter#autoHideSkipButton()} is enabled.
     * A collection of segments that have automatically hidden the skip button for, and all segments in this list
     * contain the current video time.  Segment are removed when playback exits the segment.
     */
    private static final List<SponsorSegment> hiddenSkipSegmentsForCurrentVideoTime = new ArrayList<>();

    /**
     * Current segments that have been auto skipped.
     * If field is non-null then the range will always contain the current video time.
     * Range is used to prevent auto-skipping after undo.
     * Android Range object has inclusive end time, unlike {@link SponsorSegment}.
     */
    @Nullable
    private static Range<Long> undoAutoSkipRange;
    /**
     * Range to undo if the toast is tapped.
     * Is always null or identical to the last non-null value of {@link #undoAutoSkipRange}.
     */
    @Nullable
    private static Range<Long> undoAutoSkipRangeToast;

    /**
     * System time (in milliseconds) of when to hide the skip button of {@link #segmentCurrentlyPlaying}.
     * Value is zero if playback is not inside a segment ({@link #segmentCurrentlyPlaying} is null),
     * or if {@link SettingsAdapter#autoHideSkipButton()} is not enabled.
     */
    private static long skipSegmentButtonEndTime;
    @Nullable
    private static String timeWithoutSegments;
    private static int seekbarAbsoluteLeft;
    private static int seekbarAbsoluteRight;
    private static int seekbarAbsoluteTop;
    private static int seekbarAbsoluteBottom;
    private static int seekbarThickness;

    @Nullable
    private static SponsorSegment lastSegmentSkipped;
    private static long lastSegmentSkippedTime;

    @Nullable
    private static SponsorSegment toastSegmentSkipped;
    private static int toastNumberOfSegmentsSkipped;

    /**
     * The last toast dialog showing on screen.
     */
    private static WeakReference<Dialog> toastDialogRef = new WeakReference<>(null);

    /**
     * Visibility of the ad progress UI component.
     */
    private static volatile int adProgressTextVisibility = -1;

    private static Configuration config() { return SponsorBlockApi.config(); }
    private static SettingsAdapter settings() { return config().settings(); }
    private static VideoInformationAdapter video() { return config().video(); }
    private static PlayerStateAdapter playerState() { return config().playerState(); }
    private static UiBridge ui() { return config().ui(); }

    /**
     * Notification from a host-app player-type observer. Dismisses the undo toast when entering PiP.
     */
    public static void onPlayerTypeChanged() {
        if (!config().undoToastEnabled()) return;
        if (playerState().isPictureInPicture() && dismissUndoToast()) {
            Logger.printDebug(() -> "Dismissed undo toast as playback is PiP");
        }
    }

    /**
     * Injection point host apps call from their own channel-id observer.
     * If segments are already loaded for a channel that turns out to be whitelisted, discard them.
     */
    public static void onChannelIdChange(@Nullable String channelId) {
        ChannelWhitelistAdapter whitelist = config().channelWhitelist();
        if (whitelist == null) return;
        Utils.runOnMainThread(() -> {
            if (currentVideoId != null && segments != null
                    && whitelist.isChannelWhitelisted(channelId)) {
                Logger.printDebug(() -> "Channel is whitelisted, discarding loaded segments: " + channelId);
                discardSegmentsForWhitelistedChannel();
            }
        });
    }

    /**
     * @return If the toast was on screen and is now dismissed.
     */
    private static boolean dismissUndoToast() {
        Dialog toastDialog = toastDialogRef.get();
        if (toastDialog != null && toastDialog.isShowing()) {
            toastDialog.dismiss();
            return true;
        }

        return false;
    }

    /**
     * @return The adjusted duration to show the skip button, in milliseconds.
     */
    private static long getSkipButtonDuration() {
        return settings().autoHideSkipButtonDurationMs();
    }

    /**
     * @return The adjusted duration to show the skipped toast, in milliseconds.
     */
    private static long getToastDuration() {
        return settings().toastOnSkipDurationMs();
    }

    @Nullable
    public static SponsorSegment[] getSegments() {
        return segments;
    }

    private static void setSegments(SponsorSegment[] videoSegments) {
        Arrays.sort(videoSegments);
        segments = videoSegments;
        calculateTimeWithoutSegments();

        if (config().includesHighlight()
                && (SegmentCategory.HIGHLIGHT.behaviour == SKIP_AUTOMATICALLY
                || SegmentCategory.HIGHLIGHT.behaviour == CategoryBehaviour.MANUAL_SKIP)) {
            for (SponsorSegment segment : videoSegments) {
                if (segment.category == SegmentCategory.HIGHLIGHT) {
                    highlightSegment = segment;
                    return;
                }
            }
        }
        highlightSegment = null;
    }

    public static void addUnsubmittedSegment(SponsorSegment segment) {
        Objects.requireNonNull(segment);
        if (segments == null) {
            segments = new SponsorSegment[1];
        } else {
            segments = Arrays.copyOf(segments, segments.length + 1);
        }
        segments[segments.length - 1] = segment;
        setSegments(segments);
    }

    public static void removeUnsubmittedSegments() {
        if (segments == null || segments.length == 0) {
            return;
        }

        List<SponsorSegment> replacement = new ArrayList<>();
        for (SponsorSegment segment : segments) {
            if (segment.category != SegmentCategory.UNSUBMITTED) {
                replacement.add(segment);
            }
        }
        if (replacement.size() != segments.length) {
            setSegments(replacement.toArray(new SponsorSegment[0]));
        }
    }

    public static boolean videoHasSegments() {
        return segments != null && segments.length > 0;
    }

    /**
     * Clear all data.
     */
    private static void clearData() {
        currentVideoId = null;
        segments = null;
        highlightSegment = null;
        highlightSegmentInitialShowEndTime = 0;
        timeWithoutSegments = null;
        segmentCurrentlyPlaying = null;
        scheduledUpcomingSegment = null;
        scheduledHideSegment = null;
        skipSegmentButtonEndTime = 0;
        toastSegmentSkipped = null;
        toastNumberOfSegmentsSkipped = 0;
        undoAutoSkipRange = null;
        undoAutoSkipRangeToast = null;
        hiddenSkipSegmentsForCurrentVideoTime.clear();
    }

    private static void maybeShowWhitelistToast() {
        BooleanSetting toast = settings().toastOnWhitelistedChannel();
        if (toast != null && toast.get()) {
            Utils.showToastShort(str("morphe_sb_channel_whitelisted_toast"));
        }
    }

    /**
     * Discard segments when a whitelisted channel ID arrives after the download already completed.
     */
    private static void discardSegmentsForWhitelistedChannel() {
        String videoId = currentVideoId;
        clearData();
        currentVideoId = videoId;
        ui().hideAll();
        maybeShowWhitelistToast();
    }

    /**
     * Injection point.
     * Initializes SponsorBlock when the video player starts playing a new video.
     */
    public static void initialize() {
        try {
            Utils.verifyOnMainThread();
            // Loads each active category's behavior and color from settings, then refreshes the
            // API request string. Done on every initialize so behavior/color changes made in the
            // settings UI take effect on the next track without requiring an app restart.
            SegmentCategory.loadAllCategoriesFromSettings();
            clearData();
            ui().hideAll();
            ui().clearUnsubmittedSegmentTimes();
            Logger.printDebug(() -> "Initialized SponsorBlock");
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setCurrentVideoId(@Nullable String videoId) {
        try {
            if (videoId == null || videoId.isBlank()) {
                Logger.printDebug(() -> "Ignoring blank videoId");
                return;
            }

            if (Objects.equals(currentVideoId, videoId)) {
                return;
            }
            clearData();
            if (!settings().sbEnabled().get()) {
                return;
            }
            // Cannot use PlayerType to check because on some newer targets
            // the player type can be updated out of order and incorrectly
            // is "none" when the regular player is open
            if (playerState().isShortsOpen()) {
                Logger.printDebug(() -> "Ignoring Short");
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Network not connected, ignoring video");
                return;
            }

            currentVideoId = videoId;
            Logger.printDebug(() -> "New video ID: " + videoId);

            ChannelWhitelistAdapter whitelist = config().channelWhitelist();
            if (whitelist != null && whitelist.isCurrentChannelWhitelisted()) {
                Logger.printDebug(() -> "Skipping SponsorBlock request for whitelisted channel");
                maybeShowWhitelistToast();
                return;
            }

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

    /**
     * Must be called off main thread.
     */
    public static void executeDownloadSegments(String videoId) {
        Objects.requireNonNull(videoId);
        Utils.verifyOffMainThread();

        SponsorSegment[] segments = SBRequester.getSegments(videoId);

        Utils.runOnMainThread(() -> {
            if (!videoId.equals(currentVideoId)) {
                // user changed videos before get segments network call could complete
                Logger.printDebug(() -> "Ignoring segments for prior video: " + videoId);
                return;
            }
            ChannelWhitelistAdapter whitelist = config().channelWhitelist();
            if (whitelist != null && whitelist.isCurrentChannelWhitelisted()) {
                Logger.printDebug(() -> "Skipping SponsorBlock for whitelisted channel");
                maybeShowWhitelistToast();
                return;
            }
            setSegments(segments);

            final long videoTime = video().getVideoTime();
            if (highlightSegment != null) {
                // If the current video time is before the highlight.
                final long timeUntilHighlight = highlightSegment.start - videoTime;
                if (timeUntilHighlight > 0) {
                    if (highlightSegment.shouldAutoSkip()) {
                        skipSegment(highlightSegment, false);
                        return;
                    }
                    highlightSegmentInitialShowEndTime = System.currentTimeMillis() + Math.min(
                            (long) (timeUntilHighlight / video().getPlaybackSpeed()),
                            getSkipButtonDuration());
                }
            }

            // check for any skips now, instead of waiting for the next update to setVideoTime()
            setVideoTime(videoTime);
        });
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("unused")
    public static void setAdProgressTextVisibility(int visibility) {
        if (adProgressTextVisibility != visibility) {
            adProgressTextVisibility = visibility;

            Logger.printDebug(() -> {
                String visibilityMessage = switch (visibility) {
                    case View.VISIBLE   -> "VISIBLE";
                    case View.GONE      -> "GONE";
                    case View.INVISIBLE -> "INVISIBLE";
                    default -> "UNKNOWN";
                };
                return "AdProgressText visibility changed to: " + visibilityMessage;
            });
        }
    }

    /**
     * When a video ad is playing in a regular video player, segments or the Skip button should be hidden.
     * @return Whether the Ad Progress TextView is visible in the regular video player.
     */
    public static boolean isAdProgressTextVisible() {
        // The field stays at -1 unless the host-app patch wires setAdProgressTextVisibility().
        return adProgressTextVisibility == View.VISIBLE;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean autoHideIsEnabledAndPlayerOverlayIsActive() {
        BooleanSetting autoHide = settings().autoHideSkipButton();
        return autoHide != null && autoHide.get()
                && playerState().playerControlsVisible();
    }

    /**
     * Injection point.
     * Updates SponsorBlock every 1000ms.
     * When changing videos, this is first called with value 0 and then the video is changed.
     */
    public static void setVideoTime(long millis) {
        try {
            if (!settings().sbEnabled().get()
                    || playerState().isPlayerTypeNoneOrHidden() // Shorts playback.
                    || segments == null || segments.length == 0
                    || isAdProgressTextVisible()) {
                return;
            }
            Logger.printDebug(() -> "setVideoTime: " + millis);

            updateHiddenSegments(millis);

            final float playbackSpeed = video().getPlaybackSpeed();
            // Amount of time to look ahead for the next segment,
            // and the threshold to determine if a scheduled show/hide is at the correct video time when it's run.
            //
            // This value must be greater than the largest time between calls to this method (1000ms),
            // and must be adjusted for the video speed.
            //
            // To debug the stale skip logic, set this to a very large value (5000 or more)
            // then try manually seeking just before playback reaches a segment skip.
            final long speedAdjustedTimeThreshold = (long) (playbackSpeed * 1200);
            final long startTimerLookAheadThreshold = millis + speedAdjustedTimeThreshold;

            SponsorSegment foundSegmentCurrentlyPlaying = null;
            SponsorSegment foundUpcomingSegment = null;

            for (final SponsorSegment segment : segments) {
                if (segment.category.behaviour == CategoryBehaviour.SHOW_IN_SEEKBAR
                        || segment.category.behaviour == CategoryBehaviour.IGNORE
                        || segment.category == SegmentCategory.HIGHLIGHT) {
                    continue;
                }
                if (segment.end <= millis) {
                    continue; // Past this segment.
                }

                final boolean segmentShouldAutoSkip = shouldAutoSkipAndUndoSkipNotActive(segment, millis);

                if (segment.start <= millis) {
                    // We are in the segment!
                    if (segmentShouldAutoSkip) {
                        skipSegment(segment, false);
                        return; // Must return, as skipping causes a recursive call back into this method.
                    }

                    // First found segment, or it's an embedded segment and fully inside the outer segment.
                    if (foundSegmentCurrentlyPlaying == null || foundSegmentCurrentlyPlaying.containsSegment(segment)) {
                        // If the found segment is not currently displayed, then do not show if the segment is nearly over.
                        // This check prevents the skip button text from rapidly changing when multiple segments end at nearly the same time.
                        // Also prevents showing the skip button if user seeks into the last 800ms of the segment.
                        final long minMillisOfSegmentRemainingThreshold = 800;
                        if (segmentCurrentlyPlaying == segment
                                || !segment.endIsNear(millis, minMillisOfSegmentRemainingThreshold)) {
                            foundSegmentCurrentlyPlaying = segment;
                        } else {
                            Logger.printDebug(() -> "Ignoring segment that ends very soon: " + segment);
                        }
                    }
                    // Keep iterating and looking. There may be an upcoming autoskip,
                    // or there may be another smaller segment nested inside this segment.
                    continue;
                }

                // Segment is upcoming.
                if (startTimerLookAheadThreshold < segment.start) {
                    // Segment is not close enough to schedule, and no segments after this are of interest.
                    break;
                }

                if (segmentShouldAutoSkip) {
                    foundUpcomingSegment = segment;
                    break; // Must stop here.
                }

                // Upcoming manual skip.

                // Do not schedule upcoming segment, if it is not fully contained inside the current segment.
                if ((foundSegmentCurrentlyPlaying == null || foundSegmentCurrentlyPlaying.containsSegment(segment))
                        // Use the most inner upcoming segment.
                        && (foundUpcomingSegment == null || foundUpcomingSegment.containsSegment(segment))) {

                    // Only schedule, if the segment start time is not near the end time of the current segment.
                    // This check is needed to prevent scheduled hide and show from clashing with each other.
                    // Instead, the upcoming segment will be handled when the current segment scheduled hide calls back into this method.
                    final long minTimeBetweenStartEndOfSegments = 1000;
                    if (foundSegmentCurrentlyPlaying == null
                            || !foundSegmentCurrentlyPlaying.endIsNear(segment.start, minTimeBetweenStartEndOfSegments)) {
                        foundUpcomingSegment = segment;
                    } else {
                        Logger.printDebug(() -> "Not scheduling segment (start time is near end of current segment): " + segment);
                    }
                }
            }

            if (highlightSegment != null) {
                if (millis < getSkipButtonDuration() || (highlightSegmentInitialShowEndTime != 0
                        && System.currentTimeMillis() < highlightSegmentInitialShowEndTime)) {
                    ui().showSkipHighlightButton(highlightSegment);
                } else {
                    highlightSegmentInitialShowEndTime = 0;
                    ui().hideSkipHighlightButton();
                }
            }

            if (segmentCurrentlyPlaying != foundSegmentCurrentlyPlaying) {
                setSegmentCurrentlyPlaying(foundSegmentCurrentlyPlaying);
            } else if (foundSegmentCurrentlyPlaying != null
                    && skipSegmentButtonEndTime != 0
                    && skipSegmentButtonEndTime <= System.currentTimeMillis()) {
                Logger.printDebug(() -> "Auto hiding skip button for segment: " + segmentCurrentlyPlaying);
                skipSegmentButtonEndTime = 0;
                hiddenSkipSegmentsForCurrentVideoTime.add(foundSegmentCurrentlyPlaying);
                // Do not hide if auto-hide is enabled and player controls are visible.
                // Skip button will hide when the overlay controls are dismissed.
                if (!autoHideIsEnabledAndPlayerOverlayIsActive()) {
                    ui().hideSkipSegmentButton();
                }
            }

            // Schedule a hide, but only if the segment end is near.
            final SponsorSegment segmentToHide = (foundSegmentCurrentlyPlaying != null &&
                    foundSegmentCurrentlyPlaying.endIsNear(millis, speedAdjustedTimeThreshold))
                    ? foundSegmentCurrentlyPlaying
                    : null;

            if (scheduledHideSegment != segmentToHide) {
                if (segmentToHide == null) {
                    Logger.printDebug(() -> "Clearing scheduled hide: " + scheduledHideSegment);
                    scheduledHideSegment = null;
                } else {
                    scheduledHideSegment = segmentToHide;
                    Logger.printDebug(() -> "Scheduling hide segment: " + segmentToHide + " playbackSpeed: " + playbackSpeed);
                    final long delayUntilHide = (long) ((segmentToHide.end - millis) / playbackSpeed);
                    Utils.runOnMainThreadDelayed(() -> {
                        if (scheduledHideSegment != segmentToHide) {
                            Logger.printDebug(() -> "Ignoring old scheduled hide segment: " + segmentToHide);
                            return;
                        }
                        scheduledHideSegment = null;
                        if (!playerState().isPlaying()) {
                            Logger.printDebug(() -> "Ignoring scheduled hide segment as video is paused: " + segmentToHide);
                            return;
                        }

                        final long videoTime = video().getVideoTime();
                        if (!segmentToHide.endIsNear(videoTime, speedAdjustedTimeThreshold)) {
                            // Current video time is not what's expected. User paused playback.
                            Logger.printDebug(() -> "Ignoring outdated scheduled hide: " + segmentToHide
                                    + " videoInformation time: " + videoTime);
                            return;
                        }
                        Logger.printDebug(() -> "Running scheduled hide segment: " + segmentToHide);
                        // Need more than just hide the skip button, as this may have been an embedded segment
                        // Instead call back into setVideoTime to check everything again.
                        // Should not use VideoInformation time as it is less accurate,
                        // but this scheduled handler was scheduled precisely so we can just use the segment end time.
                        setSegmentCurrentlyPlaying(null);
                        setVideoTime(segmentToHide.end);
                    }, delayUntilHide);
                }
            }

            if (scheduledUpcomingSegment != foundUpcomingSegment) {
                if (foundUpcomingSegment == null) {
                    Logger.printDebug(() -> "Clearing scheduled segment: " + scheduledUpcomingSegment);
                    scheduledUpcomingSegment = null;
                } else {
                    scheduledUpcomingSegment = foundUpcomingSegment;
                    final SponsorSegment segmentToSkip = foundUpcomingSegment;

                    Logger.printDebug(() -> "Scheduling segment: " + segmentToSkip + " playbackSpeed: " + playbackSpeed);
                    final long delayUntilSkip = (long) ((segmentToSkip.start - millis) / playbackSpeed);
                    Utils.runOnMainThreadDelayed(() -> {
                        if (scheduledUpcomingSegment != segmentToSkip) {
                            Logger.printDebug(() -> "Ignoring old scheduled segment: " + segmentToSkip);
                            return;
                        }
                        scheduledUpcomingSegment = null;
                        if (!playerState().isPlaying()) {
                            Logger.printDebug(() -> "Ignoring scheduled hide segment as video is paused: " + segmentToSkip);
                            return;
                        }

                        final long videoTime = video().getVideoTime();
                        if (!segmentToSkip.startIsNear(videoTime, speedAdjustedTimeThreshold)) {
                            // Current video time is not what's expected. User paused playback.
                            Logger.printDebug(() -> "Ignoring outdated scheduled segment: " + segmentToSkip
                                    + " videoInformation time: " + videoTime);
                            return;
                        }
                        if (shouldAutoSkipAndUndoSkipNotActive(segmentToSkip, videoTime)) {
                            Logger.printDebug(() -> "Running scheduled skip segment: " + segmentToSkip);
                            skipSegment(segmentToSkip, false);
                        } else {
                            Logger.printDebug(() -> "Running scheduled show segment: " + segmentToSkip);
                            setSegmentCurrentlyPlaying(segmentToSkip);
                        }
                    }, delayUntilSkip);
                }
            }

            // Clear undo range if video time is outside the segment. Must check last.
            if (undoAutoSkipRange != null && !undoAutoSkipRange.contains(millis)) {
                Logger.printDebug(() -> "Clearing undo range as current time is now outside range: " + undoAutoSkipRange);
                undoAutoSkipRange = null;
            }
        } catch (Exception e) {
            Logger.printException(() -> "setVideoTime failure", e);
        }
    }

    /**
     * Removes all previously hidden segments that are no longer contained in the given video time.
     */
    private static void updateHiddenSegments(long currentVideoTime) {
        hiddenSkipSegmentsForCurrentVideoTime.removeIf((hiddenSegment) -> {
            if (!hiddenSegment.containsTime(currentVideoTime)) {
                Logger.printDebug(() -> "Resetting hide skip button: " + hiddenSegment);
                return true;
            }
            return false;
        });
    }

    private static void setSegmentCurrentlyPlaying(@Nullable SponsorSegment segment) {
        if (segment == null) {
            if (segmentCurrentlyPlaying != null) {
                Logger.printDebug(() -> "Hiding segment: " + segmentCurrentlyPlaying);
            }
            segmentCurrentlyPlaying = null;
            skipSegmentButtonEndTime = 0;
            ui().hideSkipSegmentButton();
            return;
        }

        segmentCurrentlyPlaying = segment;
        skipSegmentButtonEndTime = 0;

        BooleanSetting autoHide = settings().autoHideSkipButton();
        if (autoHide != null && autoHide.get()) {
            if (hiddenSkipSegmentsForCurrentVideoTime.contains(segment)) {
                // Playback exited a nested segment and the outer segment skip button was previously hidden.
                Logger.printDebug(() -> "Ignoring previously auto-hidden segment: " + segment);
                // Must set view segment so overlay controls shows the correct skip button.
                ui().setSkipSegment(segment);
                // Do not hide skip button if
                if (!autoHideIsEnabledAndPlayerOverlayIsActive()) {
                    ui().hideSkipSegmentButton();
                }
                return;
            }
            skipSegmentButtonEndTime = System.currentTimeMillis() + getSkipButtonDuration();
        }
        Logger.printDebug(() -> "Showing segment: " + segment);
        ui().showSkipSegmentButton(segment);
    }

    private static void skipSegment(SponsorSegment segmentToSkip, boolean userManuallySkipped) {
        try {
            ui().hideSkipHighlightButton();
            ui().hideSkipSegmentButton();

            final long now = System.currentTimeMillis();
            if (lastSegmentSkipped == segmentToSkip) {
                // If trying to seek to end of the video, YouTube can seek just before of the actual end.
                // (especially if the video does not end on a whole second boundary).
                // This causes additional segment skip attempts, even though it cannot seek any closer to the desired time.
                // Check for and ignore repeated skip attempts of the same segment over a small time period.
                final long minTimeBetweenSkippingSameSegment = Math.max(500,
                        (long) (500 / video().getPlaybackSpeed()));
                if (now - lastSegmentSkippedTime < minTimeBetweenSkippingSameSegment) {
                    Logger.printDebug(() -> "Ignoring skip segment request (already skipped as close as possible): " + segmentToSkip);
                    return;
                }
            }

            Logger.printDebug(() -> "Skipping segment: " + segmentToSkip + " isPlaying: " + playerState().isPlaying());
            lastSegmentSkipped = segmentToSkip;
            lastSegmentSkippedTime = now;
            setSegmentCurrentlyPlaying(null);
            scheduledHideSegment = null;
            scheduledUpcomingSegment = null;
            if (segmentToSkip == highlightSegment) {
                highlightSegmentInitialShowEndTime = 0;
            }

            // Set or update undo skip range.
            Range<Long> oldRange = undoAutoSkipRange;
            Range<Long> oldUndoAutoSkipRangeToast = undoAutoSkipRangeToast;
            Range<Long> range = segmentToSkip.getUndoRange();
            if (undoAutoSkipRange == null) {
                Logger.printDebug(() -> "Setting new undo range to: " + range);
                undoAutoSkipRange = range;
            } else {
                Range<Long> extendedRange = undoAutoSkipRange.extend(range);
                Logger.printDebug(() -> "Extending undo range from: " + undoAutoSkipRange +
                        " to: " + extendedRange);
                undoAutoSkipRange = extendedRange;
            }
            undoAutoSkipRangeToast = undoAutoSkipRange;

            // If the seek is successful, then the seek causes a recursive call back into this class.
            final boolean seekSuccessful = video().seekTo(segmentToSkip.end);
            if (!seekSuccessful) {
                // Can happen when switching videos and is normal.
                Logger.printDebug(() -> "Could not skip segment (seek unsuccessful): " + segmentToSkip);
                // Must restore prior undo otherwise manually seeking into
                // an auto skip always segment breaks always autoskip with newer app targets.
                undoAutoSkipRange = oldRange;
                undoAutoSkipRangeToast = oldUndoAutoSkipRangeToast;
                return;
            }

            if (!userManuallySkipped) {
                // Check for any smaller embedded segments, and count those as auto-skipped.
                final boolean showSkipToast = settings().toastOnSkip().get();
                for (SponsorSegment otherSegment : Objects.requireNonNull(segments)) {
                    if (otherSegment.end <= segmentToSkip.start) {
                        // Other segment does not overlap, and is before this skipped segment.
                        // This situation can only happen if a video is opened and adjusted to
                        // a later time in the video where earlier auto skip segments
                        // have not been encountered yet.
                        continue;
                    }
                    if (segmentToSkip.end <= otherSegment.start) {
                        break; // No other segments can be contained.
                    }

                    if (otherSegment == segmentToSkip ||
                            (otherSegment.category != SegmentCategory.HIGHLIGHT && segmentToSkip.containsSegment(otherSegment))) {
                        otherSegment.didAutoSkipped = true;
                        if (showSkipToast) {
                            showSkippedSegmentToast(otherSegment);
                        }
                    }
                }
            }

            if (segmentToSkip.category == SegmentCategory.UNSUBMITTED) {
                removeUnsubmittedSegments();
                ui().notifyNewSegmentPreviewed();
            } else if (config().viewTrackingEnabled()
                    && !playerState().isPaused()
                    && !segmentToSkip.recordedAsSkipped) {
                segmentToSkip.recordedAsSkipped = true;

                LongSetting timeSavedMs = settings().localTimeSavedMilliseconds();
                if (timeSavedMs != null) timeSavedMs.save(timeSavedMs.get() + segmentToSkip.length());
                IntegerSetting timeSavedCount = settings().localTimeSavedNumberSegments();
                if (timeSavedCount != null) timeSavedCount.save(timeSavedCount.get() + 1);

                BooleanSetting trackSkipCount = settings().trackSkipCount();
                if (trackSkipCount == null || trackSkipCount.get()) {
                    Utils.runOnBackgroundThread(() -> SBRequester.sendSegmentSkippedViewedRequest(segmentToSkip));
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "skipSegment failure", ex);
        }
    }

    /**
     * Checks if the segment should be auto-skipped _and_ if undo autoskip is not active.
     */
    private static boolean shouldAutoSkipAndUndoSkipNotActive(SponsorSegment segment, long currentVideoTime) {
        return segment.shouldAutoSkip() && (undoAutoSkipRange == null
                || !undoAutoSkipRange.contains(currentVideoTime));
    }

    public static boolean currentlyInsideSkippableSegment() {
        return segmentCurrentlyPlaying != null || !hiddenSkipSegmentsForCurrentVideoTime.isEmpty();
    }

    public static boolean shouldNotFadeOutPlayerOverlaySkipButton() {
        // Only fade out overlay if auto hide is enabled and a scheduled button auto hide is not scheduled.
        BooleanSetting autoHide = settings().autoHideSkipButton();
        return skipSegmentButtonEndTime != 0
                || !(autoHide != null && autoHide.get());
    }

    private static void showSkippedSegmentToast(SponsorSegment segment) {
        Utils.verifyOnMainThread();
        toastSegmentSkipped = segment;
        if (toastNumberOfSegmentsSkipped++ > 0) {
            return; // Toast is already scheduled.
        }

        // Maximum time between skips to be considered skipping multiple segments.
        final long delayToToastMilliseconds = 250;
        Utils.runOnMainThreadDelayed(() -> {
            try {
                // Do not show a toast if the user is scrubbing through a paused video.
                // Cannot do this video state check in setTime or before calling this method,
                // as the video state may not be up to date. So instead, only ignore the toast
                // just before it's about to show since the video state is up to date.
                if (playerState().isPaused()) {
                    Logger.printDebug(() -> "Ignoring scheduled toast as video state is paused");
                    return;
                }

                if (playerState().isPictureInPicture()) {
                    Logger.printDebug(() -> "Not showing autoskip toast as playback is PiP");
                    return;
                }

                if (toastSegmentSkipped == null || undoAutoSkipRangeToast == null) {
                    // Video was changed immediately after skipping segment.
                    Logger.printDebug(() -> "Ignoring old scheduled show toast");
                    return;
                }
                String message = toastNumberOfSegmentsSkipped == 1
                        ? toastSegmentSkipped.getSkippedToastText()
                        : str("morphe_sb_skipped_multiple_segments");

                if (config().undoToastEnabled()) {
                    showAutoSkipToast(message, undoAutoSkipRangeToast);
                } else {
                    Utils.showToastShort(message);
                }
            } finally {
                toastNumberOfSegmentsSkipped = 0;
                toastSegmentSkipped = null;
            }
        }, delayToToastMilliseconds);
    }

    private static void showAutoSkipToast(String messageToToast, Range<Long> rangeToUndo) {
        Objects.requireNonNull(messageToToast);
        Utils.verifyOnMainThread();

        if (playerState().isInlineMinimal()) {
            // Cannot easily show a toast since there is no layout view context.
            // Probably better to not show a toast here anyway.
            Logger.printDebug(() -> "Not showing undo toast for feed playback");
            return;
        }

        Context currentContext = ui().overlayContext();
        if (currentContext == null) {
            Logger.printException(() -> "Cannot show toast (context is null): " + messageToToast);
            return;
        }

        Logger.printDebug(() -> "Showing toast: " + messageToToast);

        Dialog dialog = new Dialog(currentContext);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Do not dismiss dialog if tapped outside the dialog bounds.
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout mainLayout = new LinearLayout(currentContext);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
        mainLayout.setGravity(Gravity.CENTER);
        mainLayout.setMinimumHeight(Dim.dp48);

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(20), null, null));
        background.getPaint().setColor(Utils.getDialogBackgroundColor());
        mainLayout.setBackground(background);

        TextView textView = new TextView(currentContext);
        textView.setText(messageToToast);
        textView.setTextSize(14);
        textView.setTextColor(Utils.getAppForegroundColor());
        textView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textParams.gravity = Gravity.CENTER;
        textView.setLayoutParams(textParams);
        mainLayout.addView(textView);
        mainLayout.setAlpha(0.8f); // Opacity for the entire dialog.

        final int fadeDurationFast = ResourceUtils.getInteger("fade_duration_fast");
        Animation fadeIn = ResourceUtils.getAnimation("fade_in");
        Animation fadeOut = ResourceUtils.getAnimation("fade_out");
        fadeIn.setDuration(fadeDurationFast);
        fadeOut.setDuration(fadeDurationFast);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation animation) { }
            public void onAnimationEnd(Animation animation) {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
            public void onAnimationRepeat(Animation animation) { }
        });

        mainLayout.setOnClickListener(v -> {
            try {
                Logger.printDebug(() -> "Undoing autoskip using range: " + rangeToUndo);
                // Restore undo autoskip range since it's already cleared by now.
                undoAutoSkipRange = rangeToUndo;
                video().seekTo(rangeToUndo.getLower());

                mainLayout.startAnimation(fadeOut);
            } catch (Exception ex) {
                Logger.printException(() -> "showToastShortWithTapAction setOnClickListener failure", ex);
                dialog.dismiss();
            }
        });
        mainLayout.setClickable(true);
        dialog.setContentView(mainLayout);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(0); // Remove window animations and use custom fade animation.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

            Utils.setDialogWindowParameters(window, Gravity.BOTTOM, 72, 60, true);
        }

        if (dismissUndoToast()) {
            Logger.printDebug(() -> "Dismissed previous skip toast that was still on screen");
        }
        toastDialogRef = new WeakReference<>(dialog);

        mainLayout.startAnimation(fadeIn);
        dialog.show();

        // Fade out and dismiss the dialog if the user does not undo the skip.
        Utils.runOnMainThreadDelayed(() -> {
            if (dialog.isShowing()) {
                mainLayout.startAnimation(fadeOut);
            }
        }, getToastDuration());
    }

    /**
     * @param segment can be either a highlight or a regular manual skip segment.
     */
    public static void onSkipSegmentClicked(SponsorSegment segment) {
        try {
            if (segment != highlightSegment && segment != segmentCurrentlyPlaying
                    && !hiddenSkipSegmentsForCurrentVideoTime.contains(segment)) {
                Logger.printException(() -> "error: segment not available to skip"); // Should never happen.
                ui().hideSkipSegmentButton();
                ui().hideSkipHighlightButton();
                return;
            }
            skipSegment(segment, true);
        } catch (Exception ex) {
            Logger.printException(() -> "onSkipSegmentClicked failure", ex);
        }
    }

    /**
     * Injection point. Stores all four sides; the two draw overloads consume either left/right
     * with a caller-supplied posY, or the full rect for centered drawing.
     */
    @SuppressWarnings("unused")
    public static void setSeekbarRectangle(@Nullable Rect seekbarRect) {
        if (seekbarRect == null) return;
        final int left = seekbarRect.left;
        final int right = seekbarRect.right;
        if (seekbarAbsoluteLeft != left || seekbarAbsoluteRight != right) {
            Logger.printDebug(() -> "setSeekbarRectangle left: " + left + " right: " + right);
            seekbarAbsoluteLeft = left;
            seekbarAbsoluteRight = right;
        }
        seekbarAbsoluteTop = seekbarRect.top;
        seekbarAbsoluteBottom = seekbarRect.bottom;
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("unused")
    public static void setSeekbarThickness(int thickness) {
        seekbarThickness = thickness;
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("unused")
    public static String appendTimeWithoutSegments(String totalTime) {
        try {
            BooleanSetting setting = settings().videoLengthWithoutSegments();
            if (setting != null && setting.get()
                    && settings().sbEnabled().get()
                    && !TextUtils.isEmpty(totalTime) && !TextUtils.isEmpty(timeWithoutSegments)) {
                // Force LTR layout, to match the same LTR video time/length layout YouTube uses for all languages
                return "\u202D" + totalTime + timeWithoutSegments; // u202D = left to right override
            }
        } catch (Exception ex) {
            Logger.printException(() -> "appendTimeWithoutSegments failure", ex);
        }

        return totalTime;
    }

    private static void calculateTimeWithoutSegments() {
        final long currentVideoLength = video().getVideoLength();
        BooleanSetting setting = settings().videoLengthWithoutSegments();
        if (setting == null || !setting.get() || currentVideoLength <= 0
                || segments == null || segments.length == 0) {
            timeWithoutSegments = null;
            return;
        }

        boolean foundNonhighlightSegments = false;
        long timeWithoutSegmentsValue = currentVideoLength;

        for (int i = 0, length = segments.length; i < length; i++) {
            SponsorSegment segment = segments[i];
            if (segment.category == SegmentCategory.HIGHLIGHT) {
                continue;
            }
            foundNonhighlightSegments = true;

            long start = segment.start;
            final long end = segment.end;
            // To prevent nested segments from incorrectly counting additional time,
            // check if the segment overlaps any earlier segments.
            for (int j = 0; j < i; j++) {
                start = Math.max(start, segments[j].end);
            }
            if (start < end) {
                timeWithoutSegmentsValue -= (end - start);
            }
        }

        if (!foundNonhighlightSegments) {
            timeWithoutSegments = null;
            return;
        }

        final long hours = timeWithoutSegmentsValue / 3600000;
        final long minutes = (timeWithoutSegmentsValue / 60000) % 60;
        final long seconds = (timeWithoutSegmentsValue / 1000) % 60;
        if (hours > 0) {
            timeWithoutSegments = String.format(Locale.ENGLISH, "\u2009(%d:%02d:%02d)", hours, minutes, seconds);
        } else {
            timeWithoutSegments = String.format(Locale.ENGLISH, "\u2009(%d:%02d)", minutes, seconds);
        }
    }

    /**
     * Injection point. Caller supplies the seekbar's center Y from its draw context.
     */
    @SuppressWarnings("unused")
    public static void drawSegmentTimeBars(final Canvas canvas, final float posY) {
        try {
            if (segments == null || isAdProgressTextVisible()) return;
            final long videoLength = video().getVideoLength();
            if (videoLength <= 0) return;

            final int thicknessDiv2 = seekbarThickness / 2; // Rounds down.
            final float top = posY - (seekbarThickness - thicknessDiv2);
            final float bottom = posY + thicknessDiv2;
            final float videoMillisecondsToPixels = (1f / videoLength) * (seekbarAbsoluteRight - seekbarAbsoluteLeft);
            final float leftPadding = seekbarAbsoluteLeft;

            for (SponsorSegment segment : segments) {
                final float left = leftPadding + segment.start * videoMillisecondsToPixels;
                final float right;
                if (segment.category == SegmentCategory.HIGHLIGHT) {
                    right = left + HIGHLIGHT_SEGMENT_DRAW_BAR_WIDTH;
                } else {
                    right = leftPadding + segment.end * videoMillisecondsToPixels;
                }
                canvas.drawRect(left, top, right, bottom, segment.category.paint);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "drawSponsorTimeBars failure", ex);
        }
    }

    /**
     * Injection point. Overload without posY - draws centered on the rect captured by
     * {@link #setSeekbarRectangle(Rect)} as a single line.
     */
    @SuppressWarnings("unused")
    public static void drawSegmentTimeBars(@NonNull Canvas canvas) {
        try {
            SponsorSegment[] segs = segments;
            if (segs == null || segs.length == 0) return;

            final long videoLength = video().getVideoLength();
            if (videoLength <= 0) return;

            final float barWidth = seekbarAbsoluteRight - seekbarAbsoluteLeft;
            if (barWidth <= 0) return;

            final int rectHeight = seekbarAbsoluteBottom - seekbarAbsoluteTop;
            final int thickness = Math.max(1, seekbarThickness > 0 ? seekbarThickness : rectHeight);
            final float centerY = (seekbarAbsoluteTop + seekbarAbsoluteBottom) / 2f;

            for (SponsorSegment seg : segs) {
                if (seg.category.behaviour == CategoryBehaviour.IGNORE) continue;

                float left  = seekbarAbsoluteLeft + (float) seg.start / videoLength * barWidth;
                float right = seekbarAbsoluteLeft + (float) seg.end   / videoLength * barWidth;
                right = Math.max(left + 2, right);

                seg.category.paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                seg.category.paint.setStrokeWidth(thickness);
                canvas.drawLine(left, centerY, right, centerY, seg.category.paint);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "drawSegmentTimeBars (centered) failure", ex);
        }
    }
}
