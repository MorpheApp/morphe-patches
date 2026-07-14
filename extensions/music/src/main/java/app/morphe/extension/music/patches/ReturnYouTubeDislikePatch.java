/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike.Vote;

import android.text.SpannableString;
import android.text.Spanned;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.shared.returnyoutubedislike.requests.ReturnYouTubeDislikeAPI;

/**
 * Handles all interaction of UI patch components for Return YouTube Dislike, in YouTube Music.
 * <p>
 * Does not handle creating dislike spans or anything to do with {@link ReturnYouTubeDislikeAPI}.
 */
@SuppressWarnings("unused")
public class ReturnYouTubeDislikePatch {

    static {
        ReturnYouTubeDislike.setIsMusic(true);
    }

    /**
     * RYD data for the current track on screen.
     */
    @Nullable
    private static volatile ReturnYouTubeDislike currentVideoData;

    /**
     * Injection point.
     * <p>
     * Called when a litho text component is initially created,
     * and also when a Span is later reused again (such as scrolling off/on screen).
     * <p>
     * This method is sometimes called on the main thread, but it usually is called _off_ the main thread.
     * This method can be called multiple times for the same UI element (including after dislikes was added).
     *
     * @param original Original char sequence was created or reused by Litho.
     * @return The original char sequence (if nothing should change), or a replacement char sequence that contains dislikes.
     */
    public static CharSequence onLithoTextLoaded(Object conversionContext, CharSequence original) {
        try {
            if (!Settings.RYD_ENABLED.get()) {
                return original;
            }

            if (!conversionContext.toString().contains("segmented_like_dislike_button.")) {
                return original;
            }
            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                return original; // User enabled RYD while a track was on screen.
            }
            if (!(original instanceof Spanned)) {
                original = new SpannableString(original);
            }
            return videoData.getDislikesSpanForRegularVideo((Spanned) original, true, false);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(@Nullable String videoId) {
        try {
            if (!Settings.RYD_ENABLED.get()) {
                return;
            }
            if (videoId == null || videoId.isEmpty()) {
                return;
            }
            if (videoIdIsSame(currentVideoData, videoId)) {
                return;
            }
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Cannot fetch RYD, network is not connected");
                currentVideoData = null;
                return;
            }
            currentVideoData = ReturnYouTubeDislike.getFetchForVideoId(videoId);
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
     * Called when the user likes, dislikes, or removes their like/dislike.
     *
     * @param endpoint string that matches {@link Vote#endpoint}
     * @param videoId  video ID included in the endpoint request body
     */
    public static void sendVote(String endpoint, String videoId) {
        try {
            if (!Settings.RYD_ENABLED.get()) {
                return;
            }
            if (!Utils.isNotEmpty(videoId)) {
                Logger.printDebug(() -> "Ignore playlist votes");
                return;
            }

            ReturnYouTubeDislike videoData = currentVideoData;
            if (videoData == null) {
                Logger.printDebug(() -> "Cannot send vote, as current video data is null");
                return; // User enabled RYD while a track was minimized.
            } else if (!videoData.getVideoId().equals(videoId)) {
                Logger.printDebug(() -> "Cannot vote for video, as video id does not match"
                        + " videoData: " + videoData.getVideoId() + ", endPoint: " + videoId);
                return;
            }

            for (Vote v : Vote.values()) {
                if (v.endpoint.equals(endpoint)) {
                    // YT Music (unlike regular YouTube) invokes this click callback off the main thread,
                    // but ReturnYouTubeDislike.sendVote() requires the main thread to safely update UI state.
                    Utils.runOnMainThread(() -> videoData.sendVote(v));
                    return;
                }
            }

            Logger.printException(() -> "Unknown endpoint: " + endpoint);
        } catch (Exception ex) {
            Logger.printException(() -> "sendVote failure", ex);
        }
    }
}
