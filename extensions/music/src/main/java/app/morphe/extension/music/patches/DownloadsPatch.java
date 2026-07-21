/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.view.View;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
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

    private static void launchExternalDownloader() {
        launchExternalDownloader(VideoInformation.getVideoId());
    }

    private static void launchExternalDownloader(String videoId) {
        cachedFlyoutVideoId = "";
        // Do not clear download button label.

        ExternalDownloaderPreference.launchExternalDownloader(
                videoId, Utils.getActivity(), "https://music.youtube.com/watch?v=" + videoId);
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
    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()
                    || downloadButtonLabel.isEmpty() || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (map.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost componentHost) {
                CharSequence contentDescription = componentHost.getContentDescription();
                if (contentDescription != null && downloadButtonLabel.equals(contentDescription.toString())) {
                    final long now = System.currentTimeMillis();
                    if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                        return true;
                    }
                    lastMainPlayerDownloadTime = now;

                    launchExternalDownloader();
                    return true;
                }
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
                Logger.printDebug(() -> "Flyout isDownloadClick");
                final long now = System.currentTimeMillis();
                if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                    return true;
                }

                Object viewObj = map.get(ELEMENTS_SENDER_VIEW);
                final boolean inDialog = isViewInsideDialog(viewObj);

                String targetId = extractVideoIdFromCommand(p1);

                if (targetId == null && inDialog) {
                    targetId = cachedFlyoutVideoId;
                    Logger.printDebug(() -> "Using flyout isDownloadClick videoId: " + cachedFlyoutVideoId);
                }

                if (targetId != null && !targetId.isEmpty()) {
                    lastFlyoutDownloadTime = now;
                    launchExternalDownloader(targetId);
                    return true;

                } else if (inDialog) {
                    lastFlyoutDownloadTime = now;
                    Logger.printDebug(() -> "Now Playing Download Intercepted via Window Check.");
                    launchExternalDownloader();
                    return true;

                } else {
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
