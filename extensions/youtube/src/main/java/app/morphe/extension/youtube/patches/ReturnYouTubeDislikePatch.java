/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike.Vote;

import android.text.SpannableString;
import android.text.Spanned;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislikeButtons;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Handles all interaction of UI patch components.
 */
@SuppressWarnings("unused")
public class ReturnYouTubeDislikePatch {

    private static final Boolean RYD_ENABLED = Settings.RYD_ENABLED.get();

    /**
     * RYD data for the current video on screen.
     */
    @Nullable
    private static volatile ReturnYouTubeDislike currentVideoData;

    /**
     * Last video ID prefetched. Field is to prevent prefetching the same video ID multiple times in a row.
     */
    @Nullable
    private static volatile String lastPrefetchedVideoId;

    private static void clearData() {
        currentVideoData = null;

        // Rolling number text should not be cleared,
        // as it's used if incognito Short is opened/closed
        // while a regular video is on screen.
    }

    //
    // Litho player for both regular videos and Shorts.
    //

    /**
     * Injection point.
     * <p>
     * Logs if new litho text layout is used.
     */
    public static boolean useNewLithoTextCreation(boolean useNewLithoTextCreation) {
        // Don't force flag on/off unless debugging patch hooks,
        // because forcing off with newer YT targets causes Shorts player to show no buttons,
        // presumably because the old litho data isn't in the layout data.
        Logger.printDebug(() -> "useNewLithoTextCreation: " + useNewLithoTextCreation);
        return useNewLithoTextCreation;
    }

    /**
     * Called when a litho text component is created, and also when a Span is later reused
     * (such as scrolling off and back on screen). Usually called off the main thread, and
     * can be called several times for the same element.
     *
     * @param original Original char sequence created or reused by Litho.
     * @return The original char sequence, or a replacement that contains the dislikes.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        try {
            if (!RYD_ENABLED) {
                return original;
            }

            String identifier = contextInterface.patch_getIdentifier();
            if (identifier == null || !identifier.contains("video_action_bar.e")) {
                return original;
            }

            StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
            if (!Utils.contains(pathBuilder, "segmented_like_dislike_button.e")) {
                return original;
            }

            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                return original; // User enabled RYD while a video was on screen.
            }
            if (!(original instanceof Spanned)) {
                original = new SpannableString(original);
            }
            return videoData.getDislikesSpanForRegularVideo((Spanned) original);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    //
    // Counts drawn over the like and dislike buttons, shared with YouTube Music.
    //

    static {
        ReturnYouTubeDislikeButtons.setVideoDataSource(() -> currentVideoData);
    }

    /**
     * Injection point.
     */
    public static void onYogaSetWidth(long nodePointer, float width) {
        if (RYD_ENABLED) {
            ReturnYouTubeDislikeButtons.onYogaSetWidth(nodePointer, width);
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static CharSequence onComponentHostContentDescription(ComponentHost host,
                                                                 @Nullable CharSequence description) {
        return ReturnYouTubeDislikeButtons.onComponentHostContentDescription(host, description);
    }

    //
    // Video ID and voting hooks (all players).
    //

    /**
     * Injection point. Uses 'playback response' video ID hook to preload RYD.
     */
    public static void preloadVideoId(String videoId, boolean isShortAndOpeningOrPlaying) {
        try {
            if (!RYD_ENABLED) {
                return;
            }
            if (videoId.equals(lastPrefetchedVideoId)) {
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Cannot pre-fetch RYD, network is not connected");
                lastPrefetchedVideoId = null;
                return;
            }

            // Shorts shelf in home and subscription feed causes player response hook to be called,
            // and the 'is opening/playing' parameter will be false.
            //
            // Do not load RYD for any Shorts, including Shorts viewed in the regular player.
            // In June 2026 YouTube removed the dislike button from the Shorts player.
            // As of 2026/07/01, RYD’s like/dislike estimates for Shorts are potentially incorrect
            // because the RYD API is still accepting Shorts like/dislike submissions.
            //
            // Since users cannot dislike content in the Shorts player, the RYD estimates
            // may be heavily or entirely biased toward "everyone likes this Short",
            // making the estimated dislikes at best unreliable and at worst completely wrong.
            if (VideoInformation.lastPlayerResponseIsShort()) {
                Logger.printDebug(() -> "Ignoring short video ID: " + videoId);
                lastPrefetchedVideoId = videoId;
                return;
            }

            Logger.printDebug(() -> "Prefetching RYD for video: " + videoId);
            ReturnYouTubeDislike fetch = ReturnYouTubeDislike.getFetchForVideoId(videoId);

            lastPrefetchedVideoId = videoId;
        } catch (Exception ex) {
            Logger.printException(() -> "preloadVideoId failure", ex);
        }
    }

    /**
     * Injection point. Uses 'current playing' video ID hook. Always called on main thread.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (!RYD_ENABLED) return;
            if (videoId == null || videoId.isBlank()) {
                Logger.printDebug(() -> "Ignoring blank videoId");
                return;
            }

            PlayerType currentPlayerType = PlayerType.getCurrent();
            if (currentPlayerType.isNoneHiddenOrSlidingMinimized()) {
                // Must clear here, otherwise the wrong data can be used for a minimized regular video.
                clearData();
                return;
            }

            if (videoIdIsSame(currentVideoData, videoId)) {
                return;
            }
            Logger.printDebug(() -> "New video ID: " + videoId + " playerType: " + currentPlayerType);

            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Cannot fetch RYD, network is not connected");
                currentVideoData = null;
                return;
            }

            // Do not fetch if missing, so Shorts in regular player don't show bogus Shorts data.
            currentVideoData = ReturnYouTubeDislike.getFetchForVideoIdOrNull(videoId);
            // The compact bar can mount before the video ID is known.
            ReturnYouTubeDislikeButtons.refreshIconButtonCounts();
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    private static boolean videoIdIsSame(@Nullable ReturnYouTubeDislike fetch, @Nullable String videoId) {
        return (fetch == null && videoId == null)
                || (fetch != null && fetch.getVideoId().equals(videoId));
    }

    /**
     * Injection point.
     * <p>
     * Called when the user likes or dislikes.
     *
     * @param endpoint string that matches {@link Vote#endpoint}.
     * @param videoId  video ID included in the endpoint request body.
     */
    public static void sendVote(String endpoint, String videoId) {
        try {
            if (!RYD_ENABLED) {
                return;
            }
            if (!Utils.isNotEmpty(videoId)) {
                Logger.printDebug(() -> "Ignore playlist votes");
                return;
            }

            if (PlayerType.getCurrent().isNoneHiddenOrMinimized()) {
                return;
            }

            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                Logger.printDebug(() -> "Cannot send vote, as current video data is null");
                return; // User enabled RYD while a regular video was minimized.
            } else if (!videoIdIsSame(videoData, videoId)) {
                Logger.printDebug(() -> "Cannot vote for video, as video id does not match"
                        + " videoData: " + videoData.getVideoId() + ", endPoint: " + videoId);
                return;
            }

            for (Vote v : Vote.values()) {
                if (v.endpoint.equals(endpoint)) {
                    videoData.sendVote(v);
                    ReturnYouTubeDislikeButtons.invalidateIconButtonCounts();
                    return;
                }
            }

            Logger.printException(() -> "Unknown endpoint: " + endpoint);
        } catch (Exception ex) {
            Logger.printException(() -> "sendVote failure", ex);
        }
    }
}
