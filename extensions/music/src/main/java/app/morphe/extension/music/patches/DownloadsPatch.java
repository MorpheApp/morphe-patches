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
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
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

    private static volatile String cachedFlyoutVideoId = "";
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
    public static void onLithoTextLoaded(Object conversionContext, CharSequence original) {
        try {
            if (SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() &&
                    downloadButtonLabel.isEmpty() &&
                    conversionContext.toString().contains("music_download_button.")) {
                downloadButtonLabel = original.toString();
                Logger.printDebug(() -> "Found download button label: " + downloadButtonLabel);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse litho text", ex);
        }
    }

    /**
     * In app downloads reuse the download button hooks, so the override switch gates both targets.
     */
    private static boolean inAppDownloads() {
        return SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()
                && Settings.IN_APP_DOWNLOADS.get();
    }

    private static void startDownload() {
        startDownload(VideoInformation.getVideoId());
    }

    private static void startDownload(String videoId) {
        cachedFlyoutVideoId = "";
        // Do not clear download button label.

        if (Settings.IN_APP_DOWNLOADS.get()) {
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
     * Scans the raw bytes of the Command object looking for the specific
     * Protobuf binary signature of an 11-byte String field.
     */
    private static String extractVideoIdFromCommand(ProtocolBufferFieldInterface commandObj) {
        byte[] bytes = commandObj.toByteArray();
        if (bytes == null) {
            return null;
        }

        for (int i = 1, lastIndex = bytes.length - 11; i < lastIndex; i++) {
            // Protobuf: field tag (wire type 2, length-delimited) followed by length 11
            if (bytes[i] == 11 && (bytes[i - 1] & 0b00000111) == 2) {
                if (isLikelyVideoId(bytes, i + 1) && !isBlacklisted(bytes, i + 1)) {
                    return new String(bytes, i + 1, 11, StandardCharsets.US_ASCII);
                }
            }
        }
        return null;
    }

    /**
     * Checks if the 11 bytes at the given offset are a valid YouTube video ID character set.
     */
    private static boolean isLikelyVideoId(byte[] bytes, int offset) {
        for (int i = 0; i < 11; i++) {
            byte b = bytes[offset + i];
            // YouTube video IDs consist of [A-Za-z0-9_-]
            if (!((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '_' || b == '-')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the potential ID is blacklisted such as common Protobuf keys.
     */
    private static boolean isBlacklisted(byte[] bytes, int offset) {
        return matchesIgnoreCase(bytes, offset, "yt_") ||
                matchesIgnoreCase(bytes, offset, "video_") ||
                containsIgnoreCase(bytes, offset, 11, "download") ||
                containsIgnoreCase(bytes, offset, 11, "list_item") ||
                containsIgnoreCase(bytes, offset, 11, "button");
    }

    private static boolean matchesIgnoreCase(byte[] bytes, int offset, String target) {
        for (int i = 0, length = target.length(); i < length; i++) {
            byte b = bytes[offset + i];
            int lowerB = (b >= 'A' && b <= 'Z') ? (b + 32) : b;
            if (lowerB != target.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean containsIgnoreCase(byte[] bytes, int offset, int len, String target) {
        for (int i = 0, lastIndex = len - target.length(); i <= lastIndex; i++) {
            if (matchesIgnoreCase(bytes, offset + i, target)) {
                return true;
            }
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
    public static boolean offlineVideoEndpointOnClick(ProtocolBufferFieldInterface endpoint,
                                                       @Nullable Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()) {
                return false;
            }
            Utils.verifyOnMainThread();

            String videoId = endpoint == null ? null : extractVideoIdFromCommand(endpoint);
            if (videoId == null || videoId.isEmpty()) {
                videoId = VideoInformation.getVideoId();
            }
            if (videoId.isEmpty()) return false;

            long now = System.currentTimeMillis();
            if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return true;
            lastMainPlayerDownloadTime = now;
            startDownload(videoId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlineVideoEndpointOnClick failure", ex);
            return false;
        }
    }

    /**
     * The label is the localized download text litho gave us, so no hard coded language is needed.
     */
    private static boolean isDownloadSender(@Nullable Map<Object, Object> map) {
        if (map == null || downloadButtonLabel.isEmpty()
                || !(map.get(ELEMENTS_SENDER_VIEW) instanceof ViewGroup senderViewGroup)) {
            return false;
        }
        CharSequence description = senderViewGroup.getContentDescription();
        if (description == null) return false;

        String value = description.toString().toLowerCase(Locale.ROOT);
        String label = downloadButtonLabel.toLowerCase(Locale.ROOT);
        return value.contains(label) || label.contains(value);
    }

    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()
                    || downloadButtonLabel.isEmpty() || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (isDownloadSender(map)) {
                final long now = System.currentTimeMillis();
                if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return true;
                }
                lastMainPlayerDownloadTime = now;

                startDownload();
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "inAppDownloadButtonOnClick failure", ex);
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean commandResolverOnClick(ProtocolBufferFieldInterface p1, Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()
                    || p1 == null || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (inAppDownloads()) {
                byte[] commandBytes = p1.toByteArray();
                if (commandBytes != null && isOfflineBrowseCommand(commandBytes)) {
                    openLocalDownloads();
                    // The local screen is opened on top of the stock one rather than in place of
                    // it. Consuming the command instead leaves the app with a navigation it never
                    // finished, which it replays on the next start and cancels again.
                    return false;
                }
            }

            if (inAppDownloadButtonOnClick(map)) {
                Logger.printDebug(() -> "inAppDownloadButtonOnClicked");
                cachedFlyoutVideoId = "";
                return true;
            }

            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_FLYOUT_MENU.get()) {
                return false;
            }

            String p1String = p1.toString();
            Logger.printDebug(() -> "commandResolverOnClick: " + p1String);

            final boolean isMenuOpen = p1String.contains("[98150882]");
            if (isMenuOpen) {
                Logger.printDebug(() -> "Flyout isMenuOpen");
                String extractedId = extractVideoIdFromCommand(p1);
                if (extractedId != null) {
                    cachedFlyoutVideoId = extractedId;
                    Logger.printDebug(() -> "Found flyout isMenuOpen videoId: " + extractedId);
                } else {
                    cachedFlyoutVideoId = "";
                }
                return false;
            }

            final boolean isDownloadClick = Utils.containsAny(p1String,
                    "[133724106]", "[443434441]");
            if (isDownloadClick) {
                Object viewObj = map.get(ELEMENTS_SENDER_VIEW);

                if (viewObj == null) {
                    Logger.printDebug(() -> "Ignored programmatic download click (no sender_view).");
                    return false;
                }

                if (viewObj instanceof ViewGroup senderViewGroup) {
                    CharSequence cd = senderViewGroup.getContentDescription();
                    if (cd != null && !downloadButtonLabel.isEmpty()) {
                        String cdLower = cd.toString().toLowerCase(Locale.ROOT);
                        String labelLower = downloadButtonLabel.toLowerCase(Locale.ROOT);

                        if (!cdLower.contains(labelLower) && !labelLower.contains(cdLower)) {
                            Logger.printDebug(() -> "Ignored false positive UI click (Content description mismatch).");
                            return false;
                        }
                    }
                }

                Logger.printDebug(() -> "Flyout isDownloadClick");
                final long now = System.currentTimeMillis();
                if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return true;
                }

                final boolean inDialog = isViewInsideDialog(viewObj);
                String targetId = extractVideoIdFromCommand(p1);

                if (targetId == null && inDialog) {
                    targetId = cachedFlyoutVideoId;
                    Logger.printDebug(() -> "Using flyout isDownloadClick videoId: " + cachedFlyoutVideoId);
                }

                if (targetId != null && !targetId.isEmpty()) {
                    lastFlyoutDownloadTime = now;
                    final String flyoutId = targetId;
                    Logger.printDebug(() -> "Flyout download of " + flyoutId
                            + " inDialog=" + inDialog);
                    startDownload(targetId);
                    return true;

                } else if (inDialog) {
                    lastFlyoutDownloadTime = now;
                    // The flyout of a queue row carries no id either, so this can only be
                    // the track that is playing.
                    Logger.printDebug(() -> "Now Playing Download Intercepted via Window Check.");
                    startDownload();
                    return true;

                } else {
                    // A download click with no video id is a whole album or playlist, which the
                    // app only offers with Premium, so the stock UI handles it.
                    Logger.printDebug(() -> "Playlist Download detected via Window Check. Falling back to native UI");
                    return false;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "commandResolverOnClick failure", ex);
        }
        return false;
    }
}
