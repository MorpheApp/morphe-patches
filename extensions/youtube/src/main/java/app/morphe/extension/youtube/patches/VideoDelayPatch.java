/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class VideoDelayPatch {

    private static volatile boolean bluetoothAudioConnected;
    private static volatile boolean deviceCallbackRegistered;
    private static volatile int lastLoggedDelayMs;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * Called by the audio renderer every playback loop, so this must stay cheap.
     */
    public static long delayPositionUs(long positionUs) {
        if (positionUs == Long.MIN_VALUE) {
            return positionUs;
        }

        final int delayMs = SeekBarPreference.clampToRange(Settings.VIDEO_DELAY);
        if (delayMs == 0) {
            return positionUs;
        }

        if (Settings.VIDEO_DELAY_BLUETOOTH_ONLY.get() && !isBluetoothAudioConnected()) {
            return positionUs;
        }

        if (lastLoggedDelayMs != delayMs) {
            lastLoggedDelayMs = delayMs;
            Logger.printInfo(() -> "Delaying video by " + delayMs + " ms");
        }

        return positionUs - delayMs * 1000L;
    }

    private static boolean isBluetoothAudioConnected() {
        if (!deviceCallbackRegistered) {
            registerDeviceCallback();
        }
        return bluetoothAudioConnected;
    }

    private static synchronized void registerDeviceCallback() {
        if (deviceCallbackRegistered) return;
        deviceCallbackRegistered = true;

        try {
            Context context = Utils.getContext();
            AudioManager am = context == null ? null
                    : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                Logger.printDebug(() -> "No AudioManager, cannot detect Bluetooth audio");
                return;
            }

            // The callback is immediately called with all currently connected devices.
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    updateBluetoothState(am);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    updateBluetoothState(am);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception ex) {
            Logger.printException(() -> "registerDeviceCallback failure", ex);
        }
    }

    private static void updateBluetoothState(AudioManager am) {
        boolean connected = false;

        // noinspection WrongConstant // Suppress bogus IDE warning.
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            final int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_HEARING_AID) {
                connected = true;
                break;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && (type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                connected = true;
                break;
            }
        }

        final boolean finalConnected = connected;
        Logger.printDebug(() -> "Bluetooth audio connected: " + finalConnected);
        bluetoothAudioConnected = connected;
    }
}
