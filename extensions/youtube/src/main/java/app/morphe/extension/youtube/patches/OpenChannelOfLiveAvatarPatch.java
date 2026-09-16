/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OpenChannelOfLiveAvatarPatch {
    private static WeakReference<Activity> mainActivityRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setMainActivity(Activity activity) {
        mainActivityRef = new WeakReference<>(activity);
    }

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final String VIDEO_THUMBNAIL_VIEW_KEY =
            "VideoPresenterConstants.VIDEO_THUMBNAIL_VIEW_KEY";
    private static volatile ChannelIdRequest channelIdRequest;
    public static boolean openChannel(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        if (Settings.OPEN_CHANNEL_OF_LIVE_AVATAR.get()) {
            try {
                // Prevent a new request until the previous (if exists) is not done.
                if (channelIdRequest != null && !channelIdRequest.fetchIsDone()) {
                    return false;
                }
                // Video was opened by clicking a playlist thumbnail.
                if (playbackStartDescriptorMap.containsKey(VIDEO_THUMBNAIL_VIEW_KEY)) {
                    return false;
                }
                // Acquire the View that open the video (Live ring or Thumbnail).
                if (!(playbackStartDescriptorMap.get(ELEMENTS_SENDER_VIEW) instanceof View senderView)) {
                    return false;
                }
                // Verifies that a parent is of type Litho, ensuring that its description is not null.
                ViewParent parent = senderView.getParent();
                int parentCount = 0;
                boolean isLiveAvatar = false;
                while (parent != null && !parent.toString().contains("results")) {
                    parentCount++;

                    ViewParent loggingParent = parent;
                    final int loggingParentCount = parentCount;
                    Logger.printDebug(() -> "Live Avatar senderView parent " +
                            loggingParentCount +
                            ": " +
                            loggingParent
                    );

                    if (parent instanceof ViewGroup viewGroupParent) {
                        CharSequence description = viewGroupParent.getContentDescription();
                        boolean descriptionNull = description == null;

                        Logger.printDebug(() -> "Live Avatar viewGroupParent description is null: " +
                                descriptionNull
                        );

                        if (!descriptionNull) {
                            isLiveAvatar = true;

                            break;
                        }
                    }
                    parent = parent.getParent();
                }
                if (!isLiveAvatar) {
                    return false;
                }
                // The Live ring object takes up a small portion of the screen and an equivalent
                // height and width, compared to thumbnails or the header channel avatar.
                // This check will avoid any false positives.
                final int width = senderView.getWidth();
                final int height = senderView.getHeight();
                // The getDisplayMetrics() properties must be retrieved dynamically to avoid false positives when
                // switching between the inner and outer screens (or vice versa) on foldable devices.
                DisplayMetrics currentMetrics = Dim.getMetrics();
                boolean isLandscapeOrTablet = currentMetrics.widthPixels > currentMetrics.heightPixels;
                int maxAllowedWidth = isLandscapeOrTablet ? Dim.dp40 : Dim.dp48;
                if (width == 0 || width != height || width > maxAllowedWidth) {
                    return false;
                }

                Utils.runOnBackgroundThread(() -> {
                    channelIdRequest = ChannelIdRequest.fetchRequestIfNeeded(videoId);
                    Pair<String, String> channelInfo = channelIdRequest.getChannelInfo();
                    if (channelInfo != null) {
                        String channelId = channelInfo.second;
                        if (!TextUtils.isEmpty(channelId)) {
                            Logger.printDebug(() -> "channel ID response: " + channelId);

                            Utils.runOnMainThread(() -> {
                                var context = mainActivityRef.get();
                                if (context != null) {
                                    Intent videoChannelIntent = new Intent(Intent.ACTION_VIEW);
                                    videoChannelIntent.setData(Uri.parse("https://www.youtube.com/channel/" + channelId));
                                    videoChannelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    videoChannelIntent.setPackage(context.getPackageName());
                                    context.startActivity(videoChannelIntent);
                                }
                            });

                            return;
                        }
                    }

                    Logger.printDebug(() -> "Could not get channel ID, string parameter is null: " + videoId);
                });
                return true;
            } catch (Exception ex) {
                Logger.printException(() -> "openChannel failure", ex);
            }
        }

        return false;
    }
}
