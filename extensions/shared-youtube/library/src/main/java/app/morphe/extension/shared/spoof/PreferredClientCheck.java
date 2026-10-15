/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.ReminderDialog;

/**
 * Offers to make another client the default one, when the default client keeps failing
 * and that client plays the videos instead.
 */
public final class PreferredClientCheck {
    /**
     * Distinct videos in a row, so a few videos that only some clients can play do not count.
     */
    private static final int FAILED_VIDEOS_THRESHOLD = 3;
    private static final String CLIENT_TYPE_SETTING_KEY = "morphe_spoof_video_streams_client_type";

    @GuardedBy("PreferredClientCheck.class")
    private static final Set<String> failedVideoIds = new HashSet<>();

    private PreferredClientCheck() {
    }

    /**
     * @param preferredWorked If the first client tried returned the streams.
     * @param workingClient   The client that returned the streams, or null if none did.
     */
    public static synchronized void onStreamsFetched(String videoId, boolean preferredWorked,
                                                     @Nullable ClientType workingClient) {
        if (preferredWorked) {
            failedVideoIds.clear();
            return;
        }
        if (workingClient == null) {
            return;
        }

        failedVideoIds.add(videoId);
        if (failedVideoIds.size() < FAILED_VIDEOS_THRESHOLD) {
            return;
        }
        failedVideoIds.clear();

        try {
            showDialogIfDue(workingClient);
        } catch (Exception ex) {
            Logger.printException(() -> "showDialogIfDue failure", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static void showDialogIfDue(ClientType workingClient) {
        LongSetting lastShown = SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_CLIENT_FAILING_DIALOG_LAST_SHOWN;
        Activity activity = Utils.getActivity();
        if (activity == null || !ReminderDialog.isDue(lastShown)
                || !(Setting.getSettingFromPath(CLIENT_TYPE_SETTING_KEY) instanceof EnumSetting<?> setting)) {
            return;
        }
        // Only clients the user can select in the settings are offered, with the names shown there.
        String[] names = ResourceUtils.getStringArray(CLIENT_TYPE_SETTING_KEY + "_entries");
        List<String> values = Arrays.asList(ResourceUtils.getStringArray(CLIENT_TYPE_SETTING_KEY + "_entry_values"));
        final int currentIndex = values.indexOf(setting.get().name());
        final int workingIndex = values.indexOf(workingClient.name());
        if (names.length != values.size() || currentIndex < 0 || workingIndex < 0 || currentIndex == workingIndex) {
            return;
        }
        Logger.printDebug(() -> "Default client keeps failing, suggesting: " + workingClient);

        ReminderDialog.show(
                activity,
                lastShown,
                str("morphe_spoof_video_streams_client_failing_dialog_title"),
                str("morphe_spoof_video_streams_client_failing_dialog_message",
                        names[currentIndex], names[workingIndex]),
                str("morphe_spoof_video_streams_client_failing_dialog_switch"),
                () -> {
                    ((EnumSetting<ClientType>) setting).save(workingClient);
                    Utils.restartApp(activity);
                }
        );
    }
}
