/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2719
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings("unused")
public class BackgroundPlaybackPatch {

    public enum AutoPauseOnLockMode {
        OFF,
        ALWAYS,
        EXCEPT_WIRELESS_AUDIO
    }

    private static final boolean REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS
            = Settings.REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS.get();

    private static final boolean REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS_SHORTS
            = !Settings.DISABLE_SHORTS_BACKGROUND_PLAYBACK.get();

    private static boolean receiverRegistered;

    /**
     * Injection point. Called during app initialization via onCreateHook.
     */
    public static void initialize(VideoInformation.PlaybackController controller) {
        if (receiverRegistered) {
            return;
        }
        try {
            Utils.getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        handleScreenOff(context);
                    }
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        } finally {
            receiverRegistered = true;
        }
    }

    private static void handleScreenOff(Context context) {
        AutoPauseOnLockMode mode = Settings.AUTO_PAUSE_ON_LOCK.get();
        if (mode == AutoPauseOnLockMode.OFF || VideoState.getCurrent() != VideoState.PLAYING) {
            return;
        }

        if (mode == AutoPauseOnLockMode.EXCEPT_WIRELESS_AUDIO && isWirelessAudioConnected(context)) {
            return;
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            final long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        }
    }

    private static boolean isWirelessAudioConnected(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        // noinspection WrongConstant // Suppress bogus IDE warning.
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            final int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || type == AudioDeviceInfo.TYPE_HEARING_AID) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                        || type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean isPatchEnabled() {
        return REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS;
    }

    /**
     * Injection point.
     */
    public static boolean enableFeatureFlag(boolean original) {
        if (REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS) return true;
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean disableFeatureFlag(boolean original) {
        if (REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS) return false;
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean isBackgroundPlaybackAllowed(boolean original) {
        if (!REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS) return original;

        if (original) return true;

        // Steps to verify most edge cases (with Shorts background playback set to off):
        // 1. Open a regular video
        // 2. Minimize app (PiP should appear)
        // 3. Reopen app
        // 4. Open a Short (without closing the regular video)
        //    (try opening both Shorts in the video player suggestions AND Shorts from the home feed)
        // 5. Minimize the app (PIP should not appear)
        // 6. Reopen app
        // 7. Close the Short
        // 8. Resume playing the regular video
        // 9. Minimize the app (PiP should appear)
        if (ShortsPlayerState.isOpen()) {
            return false;
        }

        // Check if the video player is opened and it's not playing in the feed.
        PlayerType current = PlayerType.getCurrent();
        return !current.isNoneOrHidden() && current != PlayerType.INLINE_MINIMAL;
    }

    /**
     * Injection point.
     */
    public static boolean isBackgroundShortsPlaybackAllowed(boolean original) {
        return REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS_SHORTS;
    }

    /**
     * Injection point.
     */
    public static boolean isAutomaticForegroundPlaybackAllowed(boolean original) {
        return !REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS;
    }

    /**
     * Injection point.
     */
    public static boolean isAutomaticPlaybackPauseInFlyout(boolean original) {
        return !REMOVE_BACKGROUND_PLAYBACK_RESTRICTIONS;
    }
}
