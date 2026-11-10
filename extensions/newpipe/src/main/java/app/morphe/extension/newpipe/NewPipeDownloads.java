/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.newpipe;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;

import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/** Entry point installed into the existing download action by the NewPipe patch. */
public final class NewPipeDownloads {
    private NewPipeDownloads() {
    }

    /** Called on the main thread; false leaves the original download action intact. */
    public static boolean onDownload(String videoId) {
        try {
            if (SharedYouTubeSettings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return false;
            Activity activity = Utils.getActivity();
            if (activity == null) return false;
            Utils.showToastShort(str("morphe_newpipe_loading"));
            Utils.runOnBackgroundThread(() -> loadChoices(activity, videoId));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "NewPipe onDownload failure", ex);
            return false;
        }
    }

    private static void loadChoices(Activity activity, String videoId) {
        try {
            StreamInfo info = NewPipeExtractorClient.resolve(videoId);
            List<StreamChoices.Choice> choices = StreamChoices.from(info);
            Utils.runOnMainThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (choices.isEmpty()) {
                    Utils.showToastShort(str("morphe_newpipe_no_streams"));
                    return;
                }
                CharSequence[] labels = choices.stream().map(StreamChoices.Choice::label)
                        .toArray(CharSequence[]::new);
                new AlertDialog.Builder(activity)
                        .setTitle(info.getName())
                        .setItems(labels, (dialog, index) -> startDownload(activity, info.getName(), choices.get(index)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        } catch (Exception ex) {
            Logger.printException(() -> "NewPipe stream resolution failed", ex);
            Utils.showToastShort(str("morphe_newpipe_failed"));
        }
    }

    private static void startDownload(Activity activity, String title, StreamChoices.Choice choice) {
        try {
            Intent intent = new Intent(activity, NewPipeDownloadService.class)
                    .putExtra(NewPipeDownloadService.TITLE, title)
                    .putExtra(NewPipeDownloadService.PRIMARY_URL, choice.primaryUrl())
                    .putExtra(NewPipeDownloadService.SECONDARY_URL, choice.secondaryUrl())
                    .putExtra(NewPipeDownloadService.MIME_TYPE, choice.mimeType())
                    .putExtra(NewPipeDownloadService.EXTENSION, choice.extension());
            if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent);
            else activity.startService(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "NewPipe startDownload failure", ex);
            Utils.showToastShort(str("morphe_newpipe_failed"));
        }
    }
}
