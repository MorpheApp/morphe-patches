/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Central coordinator for Sonos casting within Morphe YouTube Music.
 */
public final class SonosCastManager {
    @Nullable
    private static volatile SonosDevice activeDevice = null;
    @Nullable
    private static volatile Runnable originalCastAction = null;
    private static volatile boolean bypassToGoogleCast = false;

    private static volatile String currentTitle = "YouTube Music";
    private static volatile String currentArtist = "Morphe Cast";
    @Nullable
    private static volatile String currentAlbumArtUri = null;

    @Nullable
    private static volatile WeakReference<AudioTrack> lastTrackRef = null;
    private static volatile int currentSampleRate = 48000;

    private static volatile boolean isSyncingFromSpeaker = false;
    private static volatile int lastSpeakerVolume = -1;

    @Nullable
    private static volatile WifiManager.WifiLock wifiLock = null;
    @Nullable
    private static volatile WifiManager.WifiLock lowLatencyWifiLock = null;
    @Nullable
    private static volatile PowerManager.WakeLock wakeLock = null;

    @Nullable
    private static volatile BroadcastReceiver volumeReceiver = null;
    @Nullable
    private static ScheduledExecutorService watchdogExecutor = null;
    private static int consecutiveStoppedCount = 0;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AudioResampler resampler = new AudioResampler();
    private static long prngState = 0x123456789ABCDEFL;

    private SonosCastManager() {}

    private static float nextDither() {
        long x = prngState;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        prngState = x;
        return ((float) (x & 0xFFFFFFL) / 16777216.0f) - 0.5f;
    }

    public static boolean isConnected() {
        return activeDevice != null;
    }

    @Nullable
    public static SonosDevice getActiveDevice() {
        return activeDevice;
    }

    /**
     * Entry point called from bytecode hook in Cast button performClick.
     */
    public static boolean handleCastButtonClick(View view) {
        if (!Settings.SONOS_CAST.get()) return false;
        if (bypassToGoogleCast) {
            bypassToGoogleCast = false;
            return false;
        }
        SonosCastBottomSheet.show(view);
        return true;
    }

    /**
     * Fallback dialog entry point.
     */
    public static void showCastDialog(Context context, @Nullable Runnable originalAction) {
        if (!Settings.SONOS_CAST.get()) {
            if (originalAction != null) originalAction.run();
            return;
        }
        originalCastAction = originalAction;
        SonosCastBottomSheet.show(context, null);
    }

    public static void launchGoogleCast(View view) {
        disconnect();
        bypassToGoogleCast = true;
        view.post(view::performClick);
    }

    public static void launchGoogleCast(Context context) {
        disconnect();
        final Runnable action = originalCastAction;
        if (action != null) {
            action.run();
            return;
        }
        try {
            final Class<?> activityClass = Class.forName("androidx.fragment.app.FragmentActivity");
            if (activityClass.isInstance(context)) {
                final Class<?> factoryClass = Class.forName("androidx.mediarouter.app.MediaRouteDialogFactory");
                final Object factory = factoryClass.getMethod("getDefault").invoke(null);
                final Object dialogFragment = factoryClass.getMethod("onCreateChooserDialogFragment").invoke(factory);
                final Object fm = activityClass.getMethod("getSupportFragmentManager").invoke(context);
                dialogFragment.getClass().getMethod(
                        "show",
                        Class.forName("androidx.fragment.app.FragmentManager"),
                        String.class
                ).invoke(dialogFragment, fm, "androidx.mediarouter:MediaRouteChooserDialogFragment");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Native Cast fallback invocation error", ex);
        }
    }

    /**
     * Intercepts AudioTrack.write in DefaultAudioSink.
     */
    public static int audioTrackWrite(AudioTrack track, ByteBuffer buffer, int sizeInBytes, int writeMode) {
        final SonosDevice dev = activeDevice;
        if (dev != null && Settings.SONOS_CAST.get()) {
            lastTrackRef = new WeakReference<>(track);

            try {
                track.setVolume(0.0f);
            } catch (Exception ignored) {}

            final int sr = track.getSampleRate();
            if (sr >= 32000 && sr <= 96000 && sr != currentSampleRate) {
                currentSampleRate = sr;
            }

            final int startPos = buffer.position();
            final int written = track.write(buffer, sizeInBytes, writeMode);

            if (written > 0) {
                try {
                    final ByteBuffer dup = buffer.duplicate();
                    dup.position(startPos);
                    final byte[] pcm16;

                    if (track.getAudioFormat() == AudioFormat.ENCODING_PCM_FLOAT) {
                        final byte[] floatBytes = new byte[written];
                        dup.get(floatBytes);
                        pcm16 = convertFloatTo16BitPcm(floatBytes, written);
                    } else if (track.getAudioFormat() == 21 /* ENCODING_PCM_24BIT_PACKED */) {
                        final byte[] pcm24 = new byte[written];
                        dup.get(pcm24);
                        pcm16 = convert24BitTo16BitPcm(pcm24, written);
                    } else {
                        pcm16 = new byte[written];
                        dup.get(pcm16);
                    }

                    final byte[] outPcm = (sr >= 32000 && sr <= 96000 && sr != 48000)
                            ? resampler.resampleStereo16(pcm16, pcm16.length, sr, 48000)
                            : pcm16;

                    LocalStreamServer.offer(outPcm, outPcm.length);
                } catch (Exception ex) {
                    Logger.printException(() -> "Error capturing AudioTrack buffer", ex);
                }
            }
            return written;
        } else {
            return track.write(buffer, sizeInBytes, writeMode);
        }
    }

    private static byte[] convertFloatTo16BitPcm(byte[] floatBytes, int length) {
        final int numFloats = length / 4;
        final byte[] outBytes = new byte[numFloats * 2];
        final ByteBuffer inBuf = ByteBuffer.wrap(floatBytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < numFloats; i++) {
            final float sample = inBuf.getFloat();
            final float dither = (nextDither() - nextDither()) / 32768.0f;
            final float dithered = Math.max(-1.0f, Math.min(1.0f, sample + dither));
            final short pcm16 = (short) (dithered * 32767.0f);
            outBuf.putShort(pcm16);
        }
        return outBytes;
    }

    private static byte[] convert24BitTo16BitPcm(byte[] src, int length) {
        final int samples = length / 3;
        final byte[] out = new byte[samples * 2];
        int inIdx = 0;
        int outIdx = 0;
        while (inIdx + 2 < length) {
            out[outIdx] = src[inIdx + 1];
            out[outIdx + 1] = src[inIdx + 2];
            inIdx += 3;
            outIdx += 2;
        }
        return out;
    }

    public static void onSinkPause() {}

    public static void onSinkPlay() {}

    public static void onSinkFlush() {
        resampler.reset();
        LocalStreamServer.flush();
    }

    public static void onMediaMetadata(@Nullable MediaMetadata metadata) {
        if (metadata == null || !Settings.SONOS_CAST.get()) return;
        try {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title == null) title = "YouTube Music";

            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR);
            if (artist == null) artist = "Morphe Cast";

            String artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (artUri == null) artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (artUri == null) artUri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);

            if (artUri == null || !artUri.startsWith("http")) {
                Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bitmap != null) {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                    LocalStreamServer.setAlbumArt(baos.toByteArray());
                    final SonosDevice dev = activeDevice;
                    if (dev != null) {
                        final String hostIp = LocalStreamServer.getLocalIpAddress(dev.getIp());
                        artUri = "http://" + hostIp + ":" + LocalStreamServer.PORT + "/cover.jpg";
                    }
                }
            }

            final boolean trackChanged = (!title.equals(currentTitle) || !artist.equals(currentArtist));
            currentTitle = title;
            currentArtist = artist;
            currentAlbumArtUri = artUri;

            if (trackChanged) {
                final SonosDevice dev = activeDevice;
                if (dev != null) {
                    final String endpoint = LocalStreamServer.getLocalStreamUrl(dev.getIp());
                    SonosSoapClient.setUriAndPlay(dev, endpoint, currentTitle, currentArtist, currentAlbumArtUri, null);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Error in onMediaMetadata", ex);
        }
    }

    public static void connectToDevice(SonosDevice device) {
        activeDevice = device;
        final Context context = Utils.getContext();
        acquireLocks(context);
        registerVolumeReceiver(context);
        resampler.reset();

        final WeakReference<AudioTrack> ref = lastTrackRef;
        if (ref != null) {
            final AudioTrack track = ref.get();
            if (track != null) {
                try { track.setVolume(0.0f); } catch (Exception ignored) {}
            }
        }

        final String streamEndpoint = LocalStreamServer.start(device.getIp());
        SonosSoapClient.setUriAndPlay(device, streamEndpoint, currentTitle, currentArtist, currentAlbumArtUri, null);
        startWatchdog();
    }

    public static void disconnect() {
        final SonosDevice dev = activeDevice;
        if (dev != null) {
            stopWatchdog();
            SonosSoapClient.stop(dev);
            activeDevice = null;
            releaseLocks();
            unregisterVolumeReceiver();
            resampler.reset();
            LocalStreamServer.stop();

            final WeakReference<AudioTrack> ref = lastTrackRef;
            if (ref != null) {
                final AudioTrack track = ref.get();
                if (track != null) {
                    try { track.setVolume(1.0f); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void startWatchdog() {
        stopWatchdog();
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor();
        consecutiveStoppedCount = 0;
        watchdogExecutor.scheduleWithFixedDelay(() -> {
            try {
                final SonosDevice dev = activeDevice;
                if (dev == null || !LocalStreamServer.isRunning()) return;

                final Integer speakerVol = SonosSoapClient.getVolumeSync(dev);
                if (speakerVol != null && speakerVol != lastSpeakerVolume) {
                    lastSpeakerVolume = speakerVol;
                    dev.setVolume(speakerVol);
                    syncSpeakerVolumeToPhone(speakerVol);
                }

                final boolean hasClients = LocalStreamServer.hasActiveClients();
                final String state = SonosSoapClient.getTransportState(dev);
                final boolean isInactive = ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state) || state == null);

                if (isInactive && !hasClients && LocalStreamServer.isStreamingActive()) {
                    consecutiveStoppedCount++;
                    if (consecutiveStoppedCount >= 2) {
                        consecutiveStoppedCount = 0;
                        final String endpoint = LocalStreamServer.getLocalStreamUrl(dev.getIp());
                        SonosSoapClient.setUriAndPlay(dev, endpoint, currentTitle, currentArtist, currentAlbumArtUri, null);
                    }
                } else {
                    consecutiveStoppedCount = 0;
                }
            } catch (Exception ignored) {}
        }, 2000, 2000, TimeUnit.MILLISECONDS);
    }

    private static void stopWatchdog() {
        try {
            if (watchdogExecutor != null) {
                watchdogExecutor.shutdownNow();
                watchdogExecutor = null;
            }
        } catch (Exception ignored) {}
    }

    private static void syncSpeakerVolumeToPhone(int speakerPercent) {
        mainHandler.post(() -> {
            isSyncingFromSpeaker = true;
            try {
                final Context context = Utils.getContext();
                final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    final int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    final int targetPhoneVol = (speakerPercent * maxVol) / 100;
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetPhoneVol, 0);
                    SonosCastBottomSheet.updateVolume(speakerPercent);
                }
            } catch (Exception ignored) {
            } finally {
                mainHandler.postDelayed(() -> isSyncingFromSpeaker = false, 500);
            }
        });
    }

    private static void registerVolumeReceiver(Context context) {
        try {
            final Context appCtx = context.getApplicationContext();
            final AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            final int maxVol = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

            final BroadcastReceiver receiver = new BroadcastReceiver() {
                private int lastVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);

                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent != null && "android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                        if (isSyncingFromSpeaker) return;
                        final int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                        if (streamType == AudioManager.STREAM_MUSIC) {
                            final int newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                            if (newVol >= 0 && newVol <= maxVol && newVol != lastVol && isConnected()) {
                                lastVol = newVol;
                                final int percent = (newVol * 100) / maxVol;
                                setVolume(percent);
                                SonosCastBottomSheet.updateVolume(percent);
                            }
                        }
                    }
                }
            };

            final IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            appCtx.registerReceiver(receiver, filter);
            volumeReceiver = receiver;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not register volume receiver", ex);
        }
    }

    private static void unregisterVolumeReceiver() {
        try {
            if (volumeReceiver != null) {
                Utils.getContext().getApplicationContext().unregisterReceiver(volumeReceiver);
                volumeReceiver = null;
            }
        } catch (Exception ignored) {}
    }

    private static void acquireLocks(Context context) {
        try {
            final Context appCtx = context.getApplicationContext();
            final WifiManager wifi = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifiLock == null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MorpheSonosCast:WifiLock");
                if (wifiLock != null) {
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            }
            if (wifi != null && lowLatencyWifiLock == null) {
                lowLatencyWifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MorpheSonosCast:LowLatencyLock");
                if (lowLatencyWifiLock != null) {
                    lowLatencyWifiLock.setReferenceCounted(false);
                    lowLatencyWifiLock.acquire();
                }
            }
            final PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MorpheSonosCast:WakeLock");
                if (wakeLock != null) {
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Error acquiring locks", ex);
        }
    }

    private static void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            wifiLock = null;
            if (lowLatencyWifiLock != null && lowLatencyWifiLock.isHeld()) lowLatencyWifiLock.release();
            lowLatencyWifiLock = null;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        } catch (Exception ignored) {}
    }

    public static void setVolume(int volume) {
        final SonosDevice dev = activeDevice;
        if (dev != null) {
            SonosSoapClient.setVolume(dev, volume);
        }
    }
}
