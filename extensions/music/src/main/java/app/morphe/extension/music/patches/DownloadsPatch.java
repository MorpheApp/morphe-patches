/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.music.patches.downloads.LocalDownloadManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    /**
     * Interface to use obfuscated fields.
     */
    public interface ProtocolBufferFieldInterface {
        // Exposes non-obfuscated method on an obfuscated class.
        byte[] toByteArray();
    }

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final int IGNORE_DOUBLE_CLICK_DURATION_MS = 1000;

    private static volatile String downloadButtonLabel = "";

    private static volatile long lastFlyoutDownloadTime;
    private static volatile long lastMainPlayerDownloadTime;
    private static volatile long lastLocalDownloadsOpenTime;
    /** Browse id of the stock offline tab, which the local catalogue replaces. */
    private static final byte[] OFFLINE_BROWSE_ID =
            "FEmusic_offline".getBytes(StandardCharsets.US_ASCII);

    /**
     * Injection point.
     * Usually is called of the main thread.
     */
    public static void onLithoTextLoaded(ContextInterface conversionContext, CharSequence original) {
        try {
            if (isFlyoutMenuOverridden()
                    && downloadButtonLabel.isEmpty()
                    && Utils.contains(conversionContext.patch_getPathBuilder(),
                    "music_download_button.")) {
                downloadButtonLabel = original.toString();
                Logger.printDebug(() -> "Found download button label: " + downloadButtonLabel);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "onLithoTextLoaded failure", ex);
        }
    }

    /**
     * In app downloads and the external downloader always replace the Download button.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isActionButtonOverridden() {
        return Settings.DOWNLOAD_MODE.get() != DownloadMode.DEFAULT;
    }

    /**
     * The download mode replaces the Download menu, and the external downloader
     * can replace it in the other modes too.
     */
    private static boolean isFlyoutMenuOverridden() {
        return Settings.DOWNLOAD_MODE.get() != DownloadMode.DEFAULT
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    private static boolean inAppDownloads() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP;
    }

    private static boolean flyoutMenuUsesExternalDownloader() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.EXTERNAL
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    private static void startDownload(String videoId, boolean externalDownloader) {
        if (!externalDownloader) {
            LocalDownloadManager.enqueue(videoId);
            return;
        }

        ExternalDownloaderPreference.launchExternalDownloader(
                videoId, Utils.getActivity(), "https://music.youtube.com/watch?v=" + videoId);
    }

    private static void openLocalDownloads() {
        Activity activity = Utils.getActivity();
        if (activity == null) return;

        // A single tap on the offline chip resolves its command twice, which would otherwise
        // stack a second copy of the screen on top of the first.
        final long now = System.currentTimeMillis();
        if (now - lastLocalDownloadsOpenTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return;
        lastLocalDownloadsOpenTime = now;
        Logger.printDebug(() -> "Offline tab opened, showing the local downloads");

        Intent intent = new Intent();
        intent.setClassName(activity, "com.google.android.gms.common.api.GoogleApiActivity");
        intent.setPackage(activity.getPackageName());
        intent.setData(Uri.parse(BaseActivityHook.MORPHE_DOWNLOADS_INTENT));
        activity.startActivity(intent);
    }

    private static boolean isOfflineBrowseCommand(byte[] bytes) {
        byte[] target = OFFLINE_BROWSE_ID;
        outer: for (int i = 0; i <= bytes.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Determines if the clicked view is inside a Dialog/BottomSheet by comparing
     * its Window root to the main Activity's Window root.
     */
    private static boolean isViewInsideDialog(@Nullable Object viewObj) {
        if (viewObj instanceof View view) {
            View buttonRoot = view.getRootView();

            Activity activity = Utils.getActivity();
            if (activity != null) {
                View activityRoot = activity.getWindow().getDecorView();
                return buttonRoot != activityRoot;
            }
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean offlineVideoEndpointOnClick(String videoId) {
        try {
            if (!isActionButtonOverridden()) {
                return false;
            }
            Utils.verifyOnMainThread();

            long now = System.currentTimeMillis();
            if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return true;
            lastMainPlayerDownloadTime = now;
            startDownload(videoId, !inAppDownloads());
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlineVideoEndpointOnClick failure", ex);
            return false;
        }
    }

    /**
     * The label is the localized download text litho gave us, so no hard coded language is needed.
     */
    private static boolean isPlayerDownloadButton(@Nullable Map<Object, Object> map) {
        if (map == null || downloadButtonLabel.isEmpty()
                || !(map.get(ELEMENTS_SENDER_VIEW) instanceof ViewGroup senderViewGroup)) {
            return false;
        }
        CharSequence description = senderViewGroup.getContentDescription();
        if (description == null || description.length() == 0) return false;

        String value = description.toString().toLowerCase(Locale.ROOT);
        String label = downloadButtonLabel.toLowerCase(Locale.ROOT);
        if (!value.contains(label) && !label.contains(value)) return false;

        int actionBarId = ResourceUtils.getIdIdentifier("video_action_bar");
        View view = senderViewGroup;
        while (view.getParent() instanceof View parent) {
            if (parent.getId() == actionBarId) return true;
            view = parent;
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean commandResolverOnClick(ProtocolBufferFieldInterface p1, Map<Object, Object> map) {
        try {
            if (!isFlyoutMenuOverridden()
                    || p1 == null || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            byte[] commandBytes = p1.toByteArray();
            if (inAppDownloads()) {
                if (commandBytes != null && isOfflineBrowseCommand(commandBytes)) {
                    openLocalDownloads();
                    // The local screen is opened on top of the stock one rather than in place of
                    // it. Consuming the command instead leaves the app with a navigation it never
                    // finished, which it replays on the next start and cancels again.
                    return false;
                }
            }

            Object viewObj = map.get(ELEMENTS_SENDER_VIEW);
            final boolean inDialog = isViewInsideDialog(viewObj);
            // Song menus in search results, playlists and the queue share the player's Download label.
            // Only the player action bar button should use the playing song's ID.
            if (!inDialog && isPlayerDownloadButton(map)) {
                if (!isActionButtonOverridden()) return false;

                String videoId = VideoInformation.getVideoId();
                if (videoId == null || videoId.isEmpty()) return false;

                final long now = System.currentTimeMillis();
                if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return true;
                }
                lastMainPlayerDownloadTime = now;
                startDownload(videoId, !inAppDownloads());
                return true;
            }

            String downloadParams = new ProtoMessage(commandBytes)
                    .messageField(133724106).stringField(1);
            if (!downloadParams.isEmpty()) {
                if (viewObj == null) {
                    Logger.printDebug(() -> "Ignored programmatic download click (no sender_view).");
                    return false;
                }

                Logger.printDebug(() -> "Flyout isDownloadClick");
                // Field 4 contains the video ID selected by this Download command.
                // The map's endpoint.tag entry can still refer to a previously opened player menu.
                String targetId = new ProtoMessage(Base64.decode(
                        Uri.decode(downloadParams), Base64.URL_SAFE)).stringField(4);

                if (!targetId.isEmpty()) {
                    // The song menu stays open after Download, so repeated taps can repeat the request.
                    // Keep this timer separate from the player button so a menu download of another song is not blocked.
                    final long now = System.currentTimeMillis();
                    if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                        return true;
                    }
                    lastFlyoutDownloadTime = now;
                    final String flyoutId = targetId;
                    Logger.printDebug(() -> "Flyout download of " + flyoutId
                            + " inDialog=" + inDialog);
                    startDownload(targetId, flyoutMenuUsesExternalDownloader());
                    return true;

                } else {
                    // Album and playlist commands have no video ID in field 4.
                    // Let YouTube Music handle them without consuming the song download timer.
                    return false;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "commandResolverOnClick failure", ex);
        }
        return false;
    }
}
