package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.settings.Settings.OPEN_CHANNEL_OF_LIVE_AVATAR;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.facebook.litho.ComponentHost;

import java.lang.ref.WeakReference;
import java.util.Map;

import app.morphe.extension.shared.Logger;
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

    /**
     * Injection point.
     *
     * @param playbackStartDescriptorMap map containing information about PlaybackStartDescriptor
     * @param newlyLoadedVideoId         id of the current video
     */
    public static boolean openChannel(@NonNull Map<Object, Object> playbackStartDescriptorMap, String newlyLoadedVideoId) {
        try {
            if (!OPEN_CHANNEL_OF_LIVE_AVATAR.get()) {
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
            Logger.printDebug(() -> "Litho description: " + contentDescription + "contains Resource description: " + liveRingDescription);
            if (containsMatch) {
                // Sometimes it may not match:
                // 1. In some languages, accessibility label is not provided.
                // 2. Language has changed in the app settings, and the app has not restarted.
                // In this case, fallback with the legacy method.

                StreamOrDetailsDataRequest request = SpoofVideoStreamsPatch.fetchDetails(
                        PlayerRoutes.GET_CHANNEL_FROM_ID,
                        newlyLoadedVideoId
                );
                if (request == null) {
                    Logger.printDebug(() -> "Could not get channel ID, fetch details are null: " + newlyLoadedVideoId);
                    return true;
                }
                var context = mainActivityRef.get();

                Intent videoChannelIntent = new Intent(Intent.ACTION_VIEW);
                videoChannelIntent.setData(Uri.parse("https://www.youtube.com/@" + request));
                videoChannelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                videoChannelIntent.setPackage(context.getPackageName());

                context.startActivity(videoChannelIntent);
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fetchVideoInformation failure", ex);
        }

        return false;
    }
}
