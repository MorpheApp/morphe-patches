/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.VideoDelayDialogButton;

/**
 * Lets automation apps (MacroDroid, Tasker) change the video delay,
 * for example to a different value for each Bluetooth device.
 * <p>
 * Send an explicit broadcast to this class with action {@link #ACTION_SET_VIDEO_DELAY}
 * and an integer or string extra {@link #EXTRA_DELAY_MS}.
 */
public class VideoDelayReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_VIDEO_DELAY = "app.morphe.extension.youtube.SET_VIDEO_DELAY";
    public static final String EXTRA_DELAY_MS = "delay_ms";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!ACTION_SET_VIDEO_DELAY.equals(intent.getAction())) return;

            // Settings read their saved values from the context,
            // and the app may have been started only to deliver this broadcast.
            if (!Utils.isContextSet()) {
                Utils.setContext(context.getApplicationContext());
            }

            Object extra = intent.getExtras() == null ? null : intent.getExtras().get(EXTRA_DELAY_MS);
            if (extra == null) {
                Logger.printInfo(() -> "Ignoring video delay broadcast without " + EXTRA_DELAY_MS);
                return;
            }

            final int delayMs = Integer.parseInt(extra.toString().trim());
            Settings.VIDEO_DELAY.save(delayMs);
            final int clampedMs = SeekBarPreference.clampToRange(Settings.VIDEO_DELAY);
            if (clampedMs != delayMs) {
                Settings.VIDEO_DELAY.save(clampedMs);
            }

            VideoDelayDialogButton.updateButtonAppearance();

            Logger.printInfo(() -> "Video delay set to " + clampedMs + " ms by broadcast");
        } catch (Exception ex) {
            Logger.printException(() -> "onReceive failure", ex);
        }
    }
}
