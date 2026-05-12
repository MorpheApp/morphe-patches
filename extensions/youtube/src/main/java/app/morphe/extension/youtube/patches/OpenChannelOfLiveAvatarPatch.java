/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.settings.Settings.OPEN_CHANNEL_OF_LIVE_AVATAR;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.PlayerRoutes;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;

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
     * If you change the language in the app settings, a string from another language may be used.
     * In this case, restarting the app will solve it.
     */
    private static final String liveRingDescription = str("morphe_live_ring_description");

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

    private static StreamOrDetailsDataRequest liveAvatarChannelRequest = null;
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
            if (liveAvatarChannelRequest != null && !liveAvatarChannelRequest.fetchIsDone()) {
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
            // Check content description (accessibility labels) of the live ring.
            final CharSequence contentDescription = componentHost.getContentDescription();
            if (contentDescription == null) {
                return false;
            }

            final boolean containsMatch = contentDescription.toString().contains(liveRingDescription);
            Logger.printDebug(() -> "Litho description: " + contentDescription
                    + "contains Resource description: " + liveRingDescription);
            if (containsMatch) {
                liveAvatarChannelRequest = SpoofVideoStreamsPatch.fetchDetails(
                        PlayerRoutes.GET_CHANNEL_FROM_ID,
                        videoId
                );
                Utils.runOnBackgroundThread(() -> {
                    if (liveAvatarChannelRequest.getStreamDetails() instanceof String channelID && !channelID.isEmpty()) {
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
        } catch (Exception ex) {
            Logger.printException(() -> "fetchVideoInformation failure", ex);
        }
        return false;
    }
}
