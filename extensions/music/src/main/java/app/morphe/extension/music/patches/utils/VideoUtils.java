/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.utils;

import static app.morphe.extension.shared.settings.preference.SharedExternalDownloaderPreference.showDialogIfAppIsNotInstalled;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import java.time.Duration;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class VideoUtils {

    /**
     * Triggered by DownloadsPatch via Utils.runOnMainThreadDelayed()
     */
    public static void launchExternalDownloader() {
        launchExternalDownloader(VideoInformation.getVideoId());
    }

    /**
     * Handles the actual Intent creation and app verification.
     */
    public static void launchExternalDownloader(@NonNull String videoId) {
        try {
            String downloaderPackageName = Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.get().trim();

            if (downloaderPackageName.isEmpty()) {
                Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.resetToDefault();
                downloaderPackageName = Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.defaultValue;
            }

            Context context = Utils.getContext();
            if (context == null) {
                Logger.printDebug(() -> "Failed to launch external downloader: Context is null");
                return;
            }

            if (showDialogIfAppIsNotInstalled(context, downloaderPackageName)) {
                return;
            }

            final String content = String.format("https://music.youtube.com/watch?v=%s", videoId);

            Intent intent = new Intent("android.intent.action.SEND");
            intent.setType("text/plain");
            intent.setPackage(downloaderPackageName);
            intent.putExtra("android.intent.extra.TEXT", content);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "launchExternalDownloader failure", ex);
        }
    }

    public static String getFormattedTimeStamp(long videoTime) {
        return "'" + videoTime + "' (" + getTimeStamp(videoTime) + ")\n";
    }

    @SuppressLint("DefaultLocale")
    public static String getTimeStamp(long time) {
        long hours;
        long minutes;
        long seconds;

        if (Utils.isSDKAbove(26)) {
            final Duration duration = Duration.ofMillis(time);
            hours = duration.toHours();
            minutes = duration.toMinutes() % 60;
            seconds = duration.getSeconds() % 60;
        } else {
            final long currentVideoTimeInSeconds = time / 1000;
            hours = currentVideoTimeInSeconds / (60 * 60);
            minutes = (currentVideoTimeInSeconds / 60) % 60;
            seconds = currentVideoTimeInSeconds % 60;
        }

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}
