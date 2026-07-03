/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.view.View;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    private static final String ELEMENTS_SENDER_VIEW =
            "com.google.android.libraries.youtube.rendering.elements.sender_view";
    public static final int IGNORE_DOUBLE_CLICK_DURATION_MS = 1000;

    private static String cachedFlyoutVideoId = "";
    private static String downloadButtonLabel = "";

    private static long lastFlyoutDownloadTime;
    private static long lastMainPlayerDownloadTime;

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(Object conversionContext, CharSequence original) {
        try {
            if (SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() &&
                    downloadButtonLabel.isEmpty() &&
                    conversionContext.toString().contains("music_download_button.")) {
                downloadButtonLabel = original.toString();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not parse litho text", ex);
        }
        return original;
    }

    private static void launchExternalDownloader() {
        launchExternalDownloader(VideoInformation.getVideoId());
    }

    private static void launchExternalDownloader(String videoId) {
        ExternalDownloaderPreference.launchExternalDownloader(
                videoId, Utils.getActivity(), "https://music.youtube.com/watch?v=" + videoId);
    }

    /**
     * Filters out standard 11-char strings that are known Protobuf hashes/keys.
     */
    private static boolean isValidVideoId(String id) {
        if (id == null || id.length() != 11) return false;
        String lower = id.toLowerCase();

        return !lower.startsWith("yt_") &&
                !lower.startsWith("video_") &&
                !lower.contains("download") &&
                !lower.contains("list_item") &&
                !lower.contains("button");
    }

    /**
     * Scans the raw bytes of the Command object looking for the specific
     * Protobuf binary signature of an 11-byte String field.
     */
    private static String extractVideoIdFromCommand(Object commandObj) {
        if (commandObj == null) return null;
        try {
            Method toByteArray = commandObj.getClass().getMethod("toByteArray");
            byte[] bytes = (byte[]) toByteArray.invoke(commandObj);

            if (bytes == null || bytes.length == 0) {
                return null;
            }
            for (int i = 1; i < bytes.length - 11; i++) {
                if (bytes[i] == 11) {
                    byte tagByte = bytes[i - 1];

                    if ((tagByte & 0b00000111) == 2) {
                        String possibleId = new String(bytes, i + 1, 11,
                                StandardCharsets.US_ASCII);

                        if (possibleId.matches("^[A-Za-z0-9_\\-]{11}$")) {
                            if (isValidVideoId(possibleId)) {
                                return possibleId;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "Failed to extract from Command bytes: " + e.getMessage());
        }
        return null;
    }

    /**
     * Determines if the clicked view is inside a Dialog/BottomSheet by comparing
     * its Window root to the main Activity's Window root.
     */
    private static boolean isViewInsideDialog(Object viewObj) {
        try {
            if (viewObj instanceof View view) {
                View buttonRoot = view.getRootView();

                Activity activity = Utils.getActivity();
                if (activity != null) {
                    View activityRoot = activity.getWindow().getDecorView();
                    return buttonRoot != activityRoot;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "isViewInsideDialog failure", ex);
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()
                    || downloadButtonLabel.isEmpty()) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (map != null && map.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost componentHost) {
                String description = componentHost.getContentDescription() != null ?
                        componentHost.getContentDescription().toString() : "";

                if (downloadButtonLabel.equals(description)) {
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
    public static boolean commandResolverOnClick(Object p0, Object p1, @Nullable Map<Object, Object> map) {
        try {
            if (!SharedYouTubeSettings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (inAppDownloadButtonOnClick(map)) {
                cachedFlyoutVideoId = "";
                return true;
            }

            if (p1 != null) {
                String p1Str = p1.toString();

                final boolean isMenuOpen = p1Str.contains("[98150882]");
                final boolean isDownloadClick = p1Str.contains("[133724106]");

                if (isMenuOpen) {
                    cachedFlyoutVideoId = "";

                    String extractedId = extractVideoIdFromCommand(p1);
                    if (extractedId != null) {
                        cachedFlyoutVideoId = extractedId;
                    }
                    return false;
                }

                if (isDownloadClick) {
                    final long now = System.currentTimeMillis();
                    if (now - lastFlyoutDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                        return true;
                    }

                    Object viewObj = map != null ? map.get(ELEMENTS_SENDER_VIEW) : null;
                    final boolean inDialog = isViewInsideDialog(viewObj);

                    String targetId = extractVideoIdFromCommand(p1);

                    if (targetId == null && inDialog) {
                        targetId = cachedFlyoutVideoId;
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
            }
        } catch (Exception ex) {
            Logger.printException(() -> "commandResolverOnClick failure", ex);
        }
        return false;
    }
}
