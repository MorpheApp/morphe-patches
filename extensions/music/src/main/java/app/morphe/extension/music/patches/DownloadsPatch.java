/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.patches.utils.VideoUtils;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    private static final String ELEMENTS_SENDER_VIEW = "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final String EXTERNAL_DOWNLOADER_LAUNCHED = "external_downloader_launched";

    private static String cachedFlyoutVideoId = "";
    private static String downloadButtonLabel = "";

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(@NonNull Object conversionContext, @NonNull CharSequence original) {
        try {
            if (Settings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() &&
                    downloadButtonLabel.isEmpty() &&
                    conversionContext.toString().contains("music_download_button.")) {

                downloadButtonLabel = original.toString();
            }
        } catch (Exception ignored) {
        }
        return original;
    }

    /**
     * Helper to manually construct the external downloader Intent
     */
    private static void launchExternalDownloaderWithId(String videoId) {
        try {
            Context context = Utils.getActivity();
            if (context == null) context = Utils.getContext();

            String downloaderPackageName = Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.get().trim();

            String content = "https://music.youtube.com/watch?v=" + videoId;
            Intent intent = new Intent("android.intent.action.SEND");
            intent.setType("text/plain");
            intent.setPackage(downloaderPackageName);
            intent.putExtra("android.intent.extra.TEXT", content);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "launchExternalDownloaderWithId failure", ex);
        }
    }

    /**
     * Filters out standard 11-char strings that are known Protobuf hashes/keys.
     */
    private static boolean isValidVideoId(String id) {
        if (id == null || id.length() != 11) return false;
        String lower = id.toLowerCase();

        return !lower.startsWith("yt_") &&
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

            if (bytes != null && bytes.length > 0) {
                for (int i = 1; i < bytes.length - 11; i++) {
                    if (bytes[i] == 11) {
                        byte tagByte = bytes[i - 1];

                        if ((tagByte & 0b00000111) == 2) {
                            String possibleId = new String(bytes, i + 1, 11, StandardCharsets.US_ASCII);

                            if (possibleId.matches("^[A-Za-z0-9_\\-]{11}$")) {
                                if (isValidVideoId(possibleId)) {
                                    return possibleId;
                                }
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
     * Injection point.
     */
    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (!Settings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() || downloadButtonLabel.isEmpty()) {
                return false;
            }

            if (map != null && map.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost componentHost) {
                String description = componentHost.getContentDescription() != null ?
                        componentHost.getContentDescription().toString() : "";

                if (downloadButtonLabel.equals(description)) {
                    if (!map.containsKey(EXTERNAL_DOWNLOADER_LAUNCHED)) {
                        map.put(EXTERNAL_DOWNLOADER_LAUNCHED, Boolean.TRUE);
                        Utils.runOnMainThreadDelayed(VideoUtils::launchExternalDownloader, 0);
                    }
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
            if (!Settings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()) {
                return false;
            }

            if (inAppDownloadButtonOnClick(map)) {
                cachedFlyoutVideoId = "";
                return true;
            }

            if (map != null && p1 != null) {
                String p1Str = p1.toString();

                if (p1Str.contains("[98150882]")) {
                    String extractedId = extractVideoIdFromCommand(p1);
                    if (extractedId != null) {
                        cachedFlyoutVideoId = extractedId;
                        Logger.printDebug(() -> "Cached TARGET Flyout Video ID: " + cachedFlyoutVideoId);
                    }
                    return false;
                }

                if (p1Str.contains("[133724106]")) {
                    if (map.containsKey(EXTERNAL_DOWNLOADER_LAUNCHED)) {
                        return true;
                    }

                    if (!cachedFlyoutVideoId.isEmpty()) {
                        Logger.printDebug(() -> "Intercepted Download! Launching ID: " + cachedFlyoutVideoId);
                        map.put(EXTERNAL_DOWNLOADER_LAUNCHED, Boolean.TRUE);

                        final String finalId = cachedFlyoutVideoId;
                        Utils.runOnMainThreadDelayed(() -> launchExternalDownloaderWithId(finalId), 0);

                        cachedFlyoutVideoId = "";
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "commandResolverOnClick failure", ex);
        }
        return false;
    }
}
