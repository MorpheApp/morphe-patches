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

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.downloads.CollectionDownloadManager;
import app.morphe.extension.music.patches.downloads.LocalDownloadManager;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;

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
    private static volatile String cachedCollectionId = "";
    private static volatile String downloadButtonLabel = "";

    private static volatile long lastFlyoutDownloadTime;
    private static volatile long lastMainPlayerDownloadTime;
    private static final Pattern PLAYLIST_ID = Pattern.compile(
            "(?:OLAK5uy_[A-Za-z0-9_-]{16,}|PL(?:[A-Za-z0-9_-]{30,}|[A-Za-z0-9_-]{11}))");
    private static final Pattern ENCODED_TOKEN = Pattern.compile("[A-Za-z0-9_-]{24,}");

    /**
     * Injection point.
     * Usually is called of the main thread.
     */
    public static void onLithoTextLoaded(Object conversionContext, CharSequence original) {
        try {
            if (downloadButtonLabel.isEmpty() &&
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
        // Keep the existing click hooks, but handle the stream inside YouTube Music.
        LocalDownloadManager.enqueue(videoId);
    }

    private static void openLocalDownloads() {
        Activity activity = Utils.getActivity();
        if (activity == null) return;
        Intent intent = new Intent();
        intent.setClassName(activity, "com.google.android.gms.common.api.GoogleApiActivity");
        intent.setPackage(activity.getPackageName());
        intent.setData(Uri.parse(BaseActivityHook.MORPHE_DOWNLOADS_INTENT));
        activity.startActivity(intent);
    }

    @Nullable
    private static String extractPlaylistId(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher direct = PLAYLIST_ID.matcher(raw);
        while (direct.find()) {
            String candidate = direct.group();
            if (!candidate.startsWith("PLAYLIST_")) return candidate;
        }
        Matcher tokens = ENCODED_TOKEN.matcher(raw);
        while (tokens.find()) {
            String token = tokens.group();
            for (int offset = 0; offset < Math.min(8, token.length()); offset++) {
                try {
                    byte[] decoded = Base64.decode(token.substring(offset), Base64.URL_SAFE | Base64.NO_WRAP);
                    Matcher nested = PLAYLIST_ID.matcher(new String(decoded, StandardCharsets.ISO_8859_1));
                    while (nested.find()) {
                        String candidate = nested.group();
                        if (!candidate.startsWith("PLAYLIST_")) return candidate;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] target = value.getBytes(StandardCharsets.US_ASCII);
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
            Utils.verifyOnMainThread();
            byte[] endpointBytes = endpoint == null ? null : endpoint.toByteArray();
            String playlistId = endpointBytes == null ? null : extractPlaylistId(endpointBytes);
            if (playlistId != null) {
                CollectionDownloadManager.enqueue(playlistId.startsWith("VL") ? playlistId.substring(2) : playlistId);
                return true;
            }
            String videoId = endpoint == null ? null : extractVideoIdFromCommand(endpoint);
            if (videoId == null || videoId.isEmpty()) videoId = VideoInformation.getVideoId();
            if (videoId == null || videoId.isEmpty()) return false;

            long now = System.currentTimeMillis();
            if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) return true;
            lastMainPlayerDownloadTime = now;
            launchExternalDownloader(videoId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlineVideoEndpointOnClick failure", ex);
            return false;
        }
    }

    private static boolean isDownloadSender(@Nullable Map<Object, Object> map) {
        if (map == null || !(map.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost host)) return false;
        CharSequence description = host.getContentDescription();
        if (description == null) return false;
        String value = description.toString();
        return (!downloadButtonLabel.isEmpty() && downloadButtonLabel.equals(value)) ||
                value.toLowerCase(java.util.Locale.ROOT).contains("download") ||
                value.toLowerCase(java.util.Locale.ROOT).contains("scarica");
    }

    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            if (isDownloadSender(map)) {
                    final long now = System.currentTimeMillis();
                    if (now - lastMainPlayerDownloadTime < IGNORE_DOUBLE_CLICK_DURATION_MS) {
                        return true;
                    }
                    lastMainPlayerDownloadTime = now;

                    launchExternalDownloader();
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
            if (p1 == null || map == null) {
                return false;
            }
            Utils.verifyOnMainThread();

            byte[] commandBytes = p1.toByteArray();
            if (commandBytes != null && containsAscii(commandBytes, "FEmusic_offline")) {
                openLocalDownloads();
                return true;
            }

            String collectionId = commandBytes == null ? null : extractPlaylistId(commandBytes);
            if (collectionId != null && isDownloadSender(map)) {
                CollectionDownloadManager.enqueue(collectionId.startsWith("VL") ? collectionId.substring(2) : collectionId);
                return true;
            }

            String commandCollectionId = commandBytes == null ? null : extractPlaylistId(commandBytes);
            if (commandCollectionId != null) cachedCollectionId = commandCollectionId.startsWith("VL")
                    ? commandCollectionId.substring(2) : commandCollectionId;
            if (isDownloadSender(map) && !cachedCollectionId.isEmpty()) {
                CollectionDownloadManager.enqueue(cachedCollectionId);
                return true;
            }

            if (inAppDownloadButtonOnClick(map)) {
                Logger.printDebug(() -> "inAppDownloadButtonOnClicked");
                cachedFlyoutVideoId = "";
                return true;
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
