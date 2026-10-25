/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.youtube.patches.InAppDownloadsPatch;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest;
import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest.PlaylistItem;
import app.morphe.extension.youtube.settings.Settings;

/** Opens a reviewable batch-download queue backed by the signed-in Watch Later playlist. */
@SuppressWarnings({"unused", "deprecation"})
public class OfflineQueuePreference extends Preference {

    public OfflineQueuePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public OfflineQueuePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public OfflineQueuePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public OfflineQueuePreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(preference -> {
            open(preference.getContext());
            return true;
        });
    }

    private static void open(Context context) {
        if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP) {
            Utils.showToastLong(str("morphe_offline_queue_requires_in_app"));
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastLong(str("morphe_offline_queue_sign_in"));
            return;
        }

        AlertDialog loading = new AlertDialog.Builder(context)
                .setTitle(str("morphe_offline_queue_title"))
                .setMessage(str("morphe_offline_queue_loading"))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        loading.show();

        Utils.runOnBackgroundThread(() -> {
            List<PlaylistItem> items = GetPlaylistItemsRequest.fetchItems(
                    "WL", AuthUtils.getRequestHeader());
            Utils.runOnMainThread(() -> {
                if (loading.isShowing()) loading.dismiss();
                if (items == null || items.isEmpty()) {
                    Utils.showToastLong(str("morphe_offline_queue_empty"));
                    return;
                }
                showPicker(context, items);
            });
        });
    }

    private static void showPicker(Context context, List<PlaylistItem> items) {
        CharSequence[] labels = new CharSequence[items.size()];
        boolean[] selected = new boolean[items.size()];
        long selectedSeconds = 0;
        int targetHours = Math.max(1, Math.min(10, Settings.OFFLINE_QUEUE_TARGET_HOURS.get()));
        long targetSeconds = targetHours * 60L * 60L;

        for (int i = 0; i < items.size(); i++) {
            PlaylistItem item = items.get(i);
            String channel = item.channel().isEmpty() ? "" : " — " + item.channel();
            String title = item.title().isEmpty() ? item.videoId() : item.title();
            labels[i] = str("morphe_offline_queue_item",
                    formatDuration(item.durationSeconds()), title, channel);
            if (selectedSeconds < targetSeconds) {
                selected[i] = true;
                // Keep an unknown-duration row from causing every later item to be selected.
                selectedSeconds += item.durationSeconds() > 0 ? item.durationSeconds() : 30 * 60;
            }
        }

        new AlertDialog.Builder(context)
                .setTitle(str("morphe_offline_queue_title"))
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_offline_queue_download_selected"), (dialog, which) -> {
                    int queued = 0;
                    for (int i = 0; i < items.size(); i++) {
                        if (selected[i] && InAppDownloadsPatch.enqueueVideo(items.get(i).videoId())) queued++;
                    }
                    Utils.showToastLong(queued == 0
                            ? str("morphe_offline_queue_resolver_unavailable")
                            : str("morphe_offline_queue_queued", queued));
                })
                .show();
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "–:––";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
    }
}
