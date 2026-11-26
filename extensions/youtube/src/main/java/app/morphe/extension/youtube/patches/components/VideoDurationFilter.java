/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5277
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.innertube.ElementTexts.Element;
import app.morphe.extension.youtube.innertube.ElementTexts.ElementModel;
import app.morphe.extension.youtube.innertube.ElementTexts.LockupViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailBadge;
import app.morphe.extension.youtube.innertube.ElementTexts.ThumbnailOverlay;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Hides the videos of the home feed, the search results and the Subscriptions feed
 * that are shorter or longer than set.
 * <p>
 * The duration is the text of the length badge of the thumbnail, such as "45:41".
 * Live streams, Shorts, playlists and mixes have no duration, so they are not hidden.
 */
@SuppressWarnings("unused")
public final class VideoDurationFilter extends BufferPhraseFilter {

    /**
     * Hours, minutes and seconds.
     */
    private static final int MAX_DURATION_PARTS = 3;

    @Override
    protected void reparseIfNeeded() {
        // Nothing to parse. The settings are read for each video.
    }

    @Override
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
        return KeywordContentFilter.isActiveForFeedContext(contextInterface, Settings.HIDE_VIDEOS_BY_DURATION_HOME,
                Settings.HIDE_VIDEOS_BY_DURATION_SEARCH, Settings.HIDE_VIDEOS_BY_DURATION_SUBSCRIPTIONS);
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, CharSequence path, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        final long minimumMinutes = Settings.HIDE_VIDEOS_BY_DURATION_MINIMUM.get();
        final long maximumMinutes = Settings.HIDE_VIDEOS_BY_DURATION_MAXIMUM.get();
        if (minimumMinutes <= 0 && maximumMinutes <= 0) {
            return null;
        }

        try {
            ElementModel model = KeywordContentFilter.findModel(Element.parseFrom(buffer));
            if (model == null) {
                return null;
            }

            final long seconds = getDurationSeconds(model);
            if (!isOutsideRange(seconds, minimumMinutes, maximumMinutes)) {
                return null;
            }

            Logger.printDebug(() -> "Hiding video with duration: " + seconds + " seconds");
            return Long.toString(seconds);
        } catch (InvalidProtocolBufferException ex) {
            Logger.printException(() -> "matchBuffer failure", ex);
            return null;
        }
    }

    /**
     * @param minimumMinutes Zero or less if there is no minimum.
     * @param maximumMinutes Zero or less if there is no maximum.
     */
    static boolean isOutsideRange(long seconds, long minimumMinutes, long maximumMinutes) {
        if (seconds <= 0) {
            return false; // Unknown duration.
        }
        // The conversion does not overflow if a very large value is entered in the settings.
        return (minimumMinutes > 0 && seconds < TimeUnit.MINUTES.toSeconds(minimumMinutes))
                || (maximumMinutes > 0 && seconds > TimeUnit.MINUTES.toSeconds(maximumMinutes));
    }

    /**
     * @return The duration of the video in seconds, or 0 if the model is not a video or has no duration.
     */
    static long getDurationSeconds(ElementModel model) {
        if (model.hasVideoWithContextModel()) {
            return parseDurationSeconds(model.getVideoWithContextModel().getData().getVideoData()
                    .getThumbnail().getTimestampText());
        }

        LockupViewModel lockup = model.getYoutubeModel().getViewModel().getLockupViewModel();
        for (ThumbnailOverlay overlay : lockup.getContentImage().getThumbnailViewModel().getOverlaysList()) {
            var badges = overlay.hasThumbnailOverlayBadgeViewModel()
                    ? overlay.getThumbnailOverlayBadgeViewModel().getThumbnailBadgesList()
                    : overlay.getThumbnailBottomOverlayViewModel().getBadgesList();
            for (ThumbnailBadge badge : badges) {
                // Other badges, such as "4K", are not a duration.
                final long seconds = parseDurationSeconds(badge.getThumbnailBadgeViewModel().getText());
                if (seconds > 0) {
                    return seconds;
                }
            }
        }
        return 0;
    }

    /**
     * @return The duration in seconds, or 0 if the text is not a duration,
     *         such as "LIVE", "Scheduled" or "249 episodes".
     */
    static long parseDurationSeconds(String text) {
        // Some languages use a period, such as "45.41" in Danish, Finnish and Indonesian.
        String[] parts = text.trim().split("[:.]", -1);
        if (parts.length < 2 || parts.length > MAX_DURATION_PARTS) {
            return 0;
        }

        long seconds = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                return 0;
            }

            long value = 0;
            for (int i = 0, length = part.length(); i < length; i++) {
                final int digit = Character.digit(part.charAt(i), 10);
                if (digit < 0) {
                    return 0;
                }
                value = value * 10 + digit;
            }
            seconds = seconds * 60 + value;
        }
        return seconds;
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_videos_by_duration_toast_broad"));
    }
}
