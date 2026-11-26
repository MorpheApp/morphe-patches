/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import static app.morphe.extension.shared.StringRef.str;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The connection to a speaker.
 */
final class SonosCastManager {

    private static final long POLL_INTERVAL_MILLIS = 2000;
    /**
     * Polls in a row that find the speaker stopped before the stream is started again.
     */
    private static final int STOPPED_POLLS_BEFORE_RESTART = 2;
    private static final int MAX_VOLUME = 100;
    private static final int UNKNOWN_VOLUME = -1;
    private static final int COVER_JPEG_QUALITY = 90;
    private static final String LOCK_TAG = "Morphe:SonosCast";
    /**
     * How long the wake lock is held if it is not renewed. Every poll renews it, so it only times out
     * if polling stops without a disconnect.
     */
    private static final long WAKE_LOCK_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    // Hidden AudioManager constants.
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    private static final ScheduledExecutorService pollExecutor = Executors.newSingleThreadScheduledExecutor();

    @Nullable
    private static volatile SonosDevice activeDevice;
    @Nullable
    private static volatile String serverUrl;
    @Nullable
    private static ScheduledFuture<?> pollTask;
    private static int stoppedPolls;

    /**
     * Last speaker volume in percent that was set or read.
     */
    private static volatile int speakerVolume = UNKNOWN_VOLUME;
    /**
     * The phone volume set from the speaker volume, so that the volume change it causes is not sent back.
     */
    private static volatile int expectedPhoneVolume = UNKNOWN_VOLUME;
    @Nullable
    private static volatile IntConsumer volumeListener;

    @Nullable
    private static volatile MediaMetadata metadata;
    @Nullable
    private static volatile TrackInfo sentTrack;
    private static int coverVersion;

    private static final PcmConverter converter = new PcmConverter();
    /**
     * The track that is streamed. Guarded by {@link #retiredTracks}.
     */
    @Nullable
    private static WeakReference<AudioTrack> streamedTrack;
    /**
     * Tracks that were streamed before the current one. A crossfade keeps writing to the outgoing
     * track for a while, and only the incoming one is streamed.
     */
    private static final Map<AudioTrack, Boolean> retiredTracks = new WeakHashMap<>();
    /**
     * Volume the player set for each track, to restore it after disconnecting.
     */
    private static final Map<AudioTrack, Float> playerVolumes = new WeakHashMap<>();

    @Nullable
    private static BroadcastReceiver volumeReceiver;
    @Nullable
    private static WifiManager.WifiLock highPerfWifiLock;
    @Nullable
    private static WifiManager.WifiLock lowLatencyWifiLock;
    @Nullable
    private static volatile PowerManager.WakeLock wakeLock;

    private SonosCastManager() {
    }

    static boolean isConnected() {
        return activeDevice != null;
    }

    @Nullable
    static SonosDevice getActiveDevice() {
        return activeDevice;
    }

    /**
     * @return The speaker volume in percent, or -1 if not known yet.
     */
    static int getSpeakerVolume() {
        return speakerVolume;
    }

    /**
     * @param listener Called on the main thread when the speaker volume changes, or null to remove it.
     */
    static void setVolumeListener(@Nullable IntConsumer listener) {
        volumeListener = listener;
    }

    static void connect(SonosDevice device) {
        disconnect();

        final String url;
        try {
            url = LocalStreamServer.start(device.ip);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not start the stream server", ex);
            Utils.showToastLong(str("morphe_music_sonos_cast_connection_failed", device.name));
            return;
        }

        Logger.printInfo(() -> "Connecting to " + device);
        serverUrl = url;
        activeDevice = device;
        speakerVolume = UNKNOWN_VOLUME;
        sentTrack = null;

        applyPlayerVolumes(true);
        final Context context = Utils.getContext();
        acquireLocks(context);
        registerVolumeReceiver(context);

        sendTrackIfChanged(true);
        stoppedPolls = 0;
        pollTask = pollExecutor.scheduleWithFixedDelay(SonosCastManager::poll,
                POLL_INTERVAL_MILLIS, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    static void disconnect() {
        final SonosDevice device = activeDevice;
        if (device == null) {
            return;
        }

        Logger.printInfo(() -> "Disconnecting from " + device);
        activeDevice = null;
        serverUrl = null;
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }

        SonosSoapClient.stop(device);
        LocalStreamServer.stop();
        unregisterVolumeReceiver();
        releaseLocks();
        applyPlayerVolumes(false);
    }

    static void setSpeakerVolume(int volume) {
        final SonosDevice device = activeDevice;
        if (device != null) {
            speakerVolume = volume;
            SonosSoapClient.setVolume(device, volume);
        }
    }

    /**
     * @return The volume to apply to the track.
     */
    static float onPlayerVolume(AudioTrack track, float volume) {
        synchronized (playerVolumes) {
            playerVolumes.put(track, volume);
        }
        return isConnected() ? 0 : volume;
    }

    /**
     * Mutes all tracks, or restores the volume of the player.
     */
    private static void applyPlayerVolumes(boolean mute) {
        synchronized (playerVolumes) {
            for (Map.Entry<AudioTrack, Float> entry : playerVolumes.entrySet()) {
                entry.getKey().setVolume(mute ? 0 : entry.getValue());
            }
        }
    }

    static void onAudioWritten(AudioTrack track, ByteBuffer audio) {
        synchronized (retiredTracks) {
            final AudioTrack current = (streamedTrack == null) ? null : streamedTrack.get();
            if (track != current) {
                if (retiredTracks.containsKey(track)) {
                    return;
                }
                if (current != null) {
                    retiredTracks.put(current, Boolean.TRUE);
                }
                streamedTrack = new WeakReference<>(track);
                converter.reset();
                Logger.printDebug(() -> "Streaming track with encoding: " + track.getAudioFormat()
                        + " channels: " + track.getChannelCount() + " sample rate: " + track.getSampleRate());
            }

            final byte[] converted = converter.convert(audio, track.getAudioFormat(),
                    track.getChannelCount(), track.getSampleRate());
            if (converted != null) {
                LocalStreamServer.offer(converted);
            }
        }
    }

    static void onMetadata(@Nullable MediaMetadata newMetadata) {
        // YouTube Music also sets metadata without an artist in the middle of a track.
        if (newMetadata == null || getArtist(newMetadata) == null) {
            return;
        }

        metadata = newMetadata;
        if (isConnected()) {
            sendTrackIfChanged(false);
        }
    }

    @Nullable
    private static String getArtist(MediaMetadata metadata) {
        final String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        return (artist != null) ? artist : metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    }

    /**
     * Starts the stream with the current track, so the speaker apps show it.
     *
     * @param force Start the stream even if the track has not changed.
     */
    private static synchronized void sendTrackIfChanged(boolean force) {
        final SonosDevice device = activeDevice;
        final String url = serverUrl;
        if (device == null || url == null) {
            return;
        }

        final MediaMetadata currentMetadata = metadata;
        TrackInfo track = null;
        if (currentMetadata != null) {
            final String metadataTitle = currentMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            final String title = (metadataTitle != null) ? metadataTitle : "";
            final String metadataArtist = getArtist(currentMetadata);
            final String artist = (metadataArtist != null) ? metadataArtist : "";
            final TrackInfo previous = sentTrack;
            if (!force && previous != null && title.equals(previous.title()) && artist.equals(previous.artist())) {
                return;
            }
            track = new TrackInfo(title, artist, getAlbumArtUrl(currentMetadata, url));
            sentTrack = track;
        }

        SonosSoapClient.playStream(device, LocalStreamServer.getStreamUrl(url), track);
    }

    /**
     * @return The URL of the cover, or null if the track has none.
     */
    @Nullable
    private static String getAlbumArtUrl(MediaMetadata metadata, String serverUrl) {
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI}) {
            final String uri = metadata.getString(key);
            if (uri != null && uri.startsWith("http")) {
                return uri;
            }
        }

        // The app usually gives the cover as a bitmap, which the stream server then serves.
        Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) {
            bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        if (bitmap == null || bitmap.isRecycled()) {
            LocalStreamServer.setCoverArt(null);
            return null;
        }

        final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, jpeg);
        LocalStreamServer.setCoverArt(jpeg.toByteArray());
        return LocalStreamServer.getCoverUrl(serverUrl, ++coverVersion);
    }

    private static void poll() {
        try {
            final SonosDevice device = activeDevice;
            if (device == null) {
                return;
            }
            renewWakeLock();

            final Integer volume = SonosSoapClient.getVolume(device);
            if (volume != null && volume != speakerVolume) {
                // Changed in a speaker app or with the speaker buttons.
                speakerVolume = volume;
                Utils.runOnMainThread(() -> onSpeakerVolumeChanged(volume));
            }

            // The speaker stops playing when the connection drops, or when another app plays on it.
            final String state = SonosSoapClient.getTransportState(device);
            final boolean isStopped = "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state);
            if (isStopped && !LocalStreamServer.hasClients()) {
                if (++stoppedPolls >= STOPPED_POLLS_BEFORE_RESTART) {
                    stoppedPolls = 0;
                    Logger.printInfo(() -> "Restarting the stream on " + device);
                    sendTrackIfChanged(true);
                }
            } else {
                stoppedPolls = 0;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "poll failure", ex);
        }
    }

    private static void onSpeakerVolumeChanged(int volume) {
        if (!isConnected()) {
            return;
        }

        final AudioManager audioManager = Utils.getContext().getSystemService(AudioManager.class);
        final int maxPhoneVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int phoneVolume = Math.round(volume * maxPhoneVolume / (float) MAX_VOLUME);
        if (phoneVolume != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            expectedPhoneVolume = phoneVolume;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, phoneVolume, 0);
        }

        final IntConsumer listener = volumeListener;
        if (listener != null) {
            listener.accept(volume);
        }
    }

    /**
     * Sends the volume buttons of the phone to the speaker.
     */
    private static void registerVolumeReceiver(Context context) {
        final AudioManager audioManager = context.getSystemService(AudioManager.class);
        final int maxPhoneVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        volumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                try {
                    if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) {
                        return;
                    }
                    final int phoneVolume = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
                    if (phoneVolume < 0 || phoneVolume == intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)) {
                        return;
                    }
                    if (phoneVolume == expectedPhoneVolume) {
                        expectedPhoneVolume = UNKNOWN_VOLUME;
                        return;
                    }

                    final int volume = Math.round(phoneVolume * MAX_VOLUME / (float) maxPhoneVolume);
                    setSpeakerVolume(volume);
                    final IntConsumer listener = volumeListener;
                    if (listener != null) {
                        listener.accept(volume);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "onReceive failure", ex);
                }
            }
        };
        // A protected system broadcast, so no export flag is needed.
        context.registerReceiver(volumeReceiver, new IntentFilter(VOLUME_CHANGED_ACTION));
    }

    private static void unregisterVolumeReceiver() {
        if (volumeReceiver != null) {
            Utils.getContext().unregisterReceiver(volumeReceiver);
            volumeReceiver = null;
        }
    }

    /**
     * Keeps Wi-Fi fast and the CPU awake, so the stream does not stall while the screen is off.
     */
    @SuppressWarnings("deprecation")
    private static void acquireLocks(Context context) {
        final WifiManager wifiManager = context.getSystemService(WifiManager.class);
        // Works in the background, but does nothing on Android 14 and later.
        highPerfWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG);
        highPerfWifiLock.setReferenceCounted(false);
        highPerfWifiLock.acquire();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Only works while the app is in the foreground.
            lowLatencyWifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LOCK_TAG);
            lowLatencyWifiLock.setReferenceCounted(false);
            lowLatencyWifiLock.acquire();
        }

        final PowerManager.WakeLock lock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
        lock.setReferenceCounted(false);
        lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
        wakeLock = lock;
    }

    private static void renewWakeLock() {
        final PowerManager.WakeLock lock = wakeLock;
        if (lock != null) {
            // Not reference counted, so this only moves the timeout.
            lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS);
        }
    }

    private static void releaseLocks() {
        if (highPerfWifiLock != null) {
            highPerfWifiLock.release();
            highPerfWifiLock = null;
        }
        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock.release();
            lowLatencyWifiLock = null;
        }
        final PowerManager.WakeLock lock = wakeLock;
        if (lock != null) {
            wakeLock = null;
            lock.release();
        }
    }
}
