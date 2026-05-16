/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.ResourceUtils.getString;
import static app.morphe.extension.youtube.settings.Settings.OPEN_CHANNEL_OF_LIVE_AVATAR;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.PlayerRoutes;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;
import app.morphe.extension.youtube.shared.CreatorChannelState;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class OpenChannelOfLiveAvatarPatch {
    private static WeakReference<Activity> mainActivityRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setMainActivity(Activity activity) {
        mainActivityRef = new WeakReference<>(activity);
    }

    /**
     * This key's value is the LithoView that opened the video (Live ring or Thumbnails).
     */
    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";

    /**
     * If the video is open by clicking live ring, this key does not exist.
     */
    private static final String VIDEO_THUMBNAIL_VIEW_KEY =
            "VideoPresenterConstants.VIDEO_THUMBNAIL_VIEW_KEY";

    private static volatile StreamOrDetailsDataRequest liveAvatarChannelRequest;
    private static volatile String lastLiveRingDescription;
    private static volatile Pattern liveRingDescriptionPattern;
    private static final UnaryOperator<String> stringNormalization =
            s -> java.text.Normalizer.normalize(
                    s.toLowerCase(),
                    java.text.Normalizer.Form.NFD
            ).replaceAll(
                    "\\p{M}",
                    ""
            );

    /**
     * Injection point.
     *
     * @param playbackStartDescriptorMap map containing information about PlaybackStartDescriptor
     * @param videoId         id of the current video
     */
    public static boolean openChannel(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        try {
            if (!OPEN_CHANNEL_OF_LIVE_AVATAR.get()) {
                return false;
            }
            // Prevent a new request until the previous (if exists) is not done
            StreamOrDetailsDataRequest request = liveAvatarChannelRequest;
            if (request != null && !request.fetchIsDone()) {
                return false;
            }
            // Video was opened by clicking the thumbnail
            if (playbackStartDescriptorMap.containsKey(VIDEO_THUMBNAIL_VIEW_KEY)) {
                return false;
            }
            // If the video was opened in the watch history, there is no VIDEO_THUMBNAIL_VIEW_KEY
            // In this case, check the view that opened the video (Live ring is litho)
            if (!(playbackStartDescriptorMap.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost componentHost)) {
                return false;
            }

            PlayerType currentPlayer = PlayerType.getCurrent();
            if (CreatorChannelState.isOpen() == (currentPlayer == PlayerType.WATCH_WHILE_MAXIMIZED)) {
                    final boolean containsMatch;

                    if (!ShortsPlayerState.isOpen()) {
                        // Check content description (accessibility labels) of the live ring.
                        final CharSequence contentDescriptionCharSequence = componentHost.getContentDescription();
                        if (contentDescriptionCharSequence == null) {
                            return false;
                        }
                        final String contentDescriptionString = contentDescriptionCharSequence.toString();

                        // If you change the language in the app settings, a string from another language may be used.
                        final String liveRingDescription = getString("morphe_live_ring_description");

                        if (!Objects.equals(lastLiveRingDescription, liveRingDescription)) {
                            liveRingDescriptionPattern = Pattern.compile(
                                    Arrays.stream(stringNormalization.apply(liveRingDescription).split("\\s+"))
                                            .map(Pattern::quote)
                                            .collect(Collectors.joining(".*?"))
                            );
                            lastLiveRingDescription = liveRingDescription;
                        }

                        containsMatch = liveRingDescriptionPattern.matcher(
                                stringNormalization.apply(contentDescriptionString)
                        ).find();

                        Logger.printDebug(() -> "Litho description: " + contentDescriptionString
                                + "\ncontains Resource description: " + liveRingDescription
                                + "\nmatch: " + containsMatch);
                    } else {
                        containsMatch = true;
                    }

                    if (containsMatch) {
                        liveAvatarChannelRequest = SpoofVideoStreamsPatch.fetchDetails(
                                PlayerRoutes.GET_CHANNEL_FROM_ID,
                                videoId
                        );
                        Utils.runOnBackgroundThread(() -> {
                            if (liveAvatarChannelRequest.getStreamDetails() instanceof String channelID && !channelID.isEmpty()) {
                                Logger.printDebug(() -> "live avatar response: " + channelID);

                                Utils.runOnMainThread(() -> {
                                    var context = mainActivityRef.get();
                                    if (context != null) {
                                        Intent videoChannelIntent = new Intent(Intent.ACTION_VIEW);
                                        videoChannelIntent.setData(Uri.parse("https://www.youtube.com/channel/" + channelID));
                                        videoChannelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        videoChannelIntent.setPackage(context.getPackageName());
                                        context.startActivity(videoChannelIntent);
                                    }
                                });
                            } else {
                                Logger.printDebug(() -> "Could not get channel ID, string parameter is null: " + videoId);
                            }
                        });
                        return true;
                    }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "openChannel failure", ex);
        }
        return false;
    }
}
