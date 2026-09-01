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

    private static boolean receiverRegistered = false;

    /**
     * Injection point. Called during app initialization via onCreateHook.
     */
    public static void initialize(VideoInformation.PlaybackController controller) {
        initialize();
    }

    /**
     * Injection point. Called during app initialization.
     */
    public static void initialize() {
        Context ctx = Utils.getContext();
        if (!receiverRegistered && ctx != null) {
            try {
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (i != null && Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                            handleScreenOff(c);
                        }
                    }
                }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
                receiverRegistered = true;
            } catch (Exception ex) {
                Logger.printException(() -> "BackgroundPlaybackPatch: Failed to register receiver", ex);
            }
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
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        }
    }

    private static boolean isWirelessAudioConnected(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices != null) {
                for (AudioDeviceInfo d : devices) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_HEARING_AID) {
                        return true;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (t == AudioDeviceInfo.TYPE_BLE_HEADSET
                                || t == AudioDeviceInfo.TYPE_BLE_SPEAKER
                                || t == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                            return true;
                        }
                    }
                }
            }
        }
        return am.isBluetoothA2dpOn() || am.isBluetoothScoOn();
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
