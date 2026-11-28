/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Keeps a cast running while the app is in the background: serves the streams of the video to the
 * Cast device and shows a notification to pause or stop.
 * <p>
 * The Cast device downloads the streams through this device while it plays,
 * so the service runs until the receiver app closes or the user stops the cast.
 */
public final class CastAdFreeService extends Service {

    private record Video(String id, double startSeconds) {
    }

    private static final String ACTION_START = "app.morphe.youtube.cast.START";
    private static final String ACTION_PLAY = "app.morphe.youtube.cast.PLAY";
    private static final String ACTION_PAUSE = "app.morphe.youtube.cast.PAUSE";
    private static final String ACTION_STOP = "app.morphe.youtube.cast.STOP";

    private static final String EXTRA_VIDEO_ID = "video_id";
    private static final String EXTRA_DEVICE_NAME = "device_name";
    private static final String EXTRA_DEVICE_ADDRESS = "device_address";
    private static final String EXTRA_DEVICE_PORT = "device_port";
    private static final String EXTRA_START_SECONDS = "start_seconds";
    private static final String DASH_CONTENT_TYPE = "application/dash+xml";
    private static final String MP4_CONTENT_TYPE = "video/mp4";

    private static final String NOTIFICATION_CHANNEL = "morphe_cast_ad_free";
    /**
     * Notification icon of the Cast library of the app.
     */
    private static final int NOTIFICATION_ICON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "cast_ic_notification_small_icon");
    private static final int NOTIFICATION_ID = NOTIFICATION_CHANNEL.hashCode();
    private static final int PROGRESS_MAX = 100;
    private static final String CAST_FOLDER = "morphe_cast";
    private static final String WAKE_LOCK_TAG = "Morphe:CastAdFree";
    /**
     * The stream urls expire after about 6 hours, so no cast lasts longer.
     */
    private static final long MAX_CAST_MILLISECONDS = 6 * 60 * 60 * 1000L;

    private static volatile boolean running;
    @Nullable
    private static volatile CastAdFreeService instance;

    /**
     * The captions the app shows, or empty for none. Kept for the next videos, as the app sends them once.
     */
    private static volatile String captionsVssId = "";

    /**
     * Shows the captions on the Cast device.
     *
     * @param vssId Captions the app shows, such as ".en", or empty for none.
     */
    static void setCaptions(String vssId) {
        captionsVssId = vssId;
        CastAdFreeService service = instance;
        @Nullable CastStreams streams = service == null ? null : service.castStreams;
        if (streams != null) {
            final int trackId = captionsTrackId(streams.captionsIndex(vssId));
            command(connection -> connection.setActiveTextTrack(trackId));
        }
    }

    private static int captionsTrackId(int captionsIndex) {
        return captionsIndex < 0 ? CastConnection.NO_TRACK : captionsIndex + 1;
    }

    /**
     * Sends a command to the running cast, if any.
     */
    static void command(Command command) {
        CastAdFreeService service = instance;
        if (service != null) {
            service.sendCommand(command);
        }
    }

    @Nullable
    private volatile Thread worker;
    @Nullable
    private volatile CastConnection connection;
    @Nullable
    private volatile CastStreams castStreams;
    /**
     * A video to cast after the current one, on the same receiver app.
     */
    private final AtomicReference<Video> nextVideo = new AtomicReference<>();
    @Nullable
    private InetAddress castHost;
    private int castPort;
    @Nullable
    private CastStreamServer server;
    @Nullable
    private volatile CastMediaSession mediaSession;
    @Nullable
    private PowerManager.WakeLock wakeLock;
    private String deviceName = "";
    private volatile boolean paused;
    private int lastProgress = -1;

    static boolean isRunning() {
        return running;
    }

    static void start(Context context, CastDeviceFinder.CastDevice device, String videoId, double startSeconds) {
        Intent intent = new Intent(context, CastAdFreeService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_DEVICE_NAME, device.name())
                .putExtra(EXTRA_DEVICE_ADDRESS, device.host().getAddress())
                .putExtra(EXTRA_DEVICE_PORT, device.port())
                .putExtra(EXTRA_START_SECONDS, startSeconds);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, CastAdFreeService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        instance = this;
        try {
            String action = intent == null ? null : intent.getAction();
            if (ACTION_START.equals(action)) {
                startCast(intent);
            } else if (ACTION_PLAY.equals(action) || ACTION_PAUSE.equals(action)) {
                setPaused(ACTION_PAUSE.equals(action));
            } else {
                stopCast(true);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStartCommand failure", ex);
            stopCast(false);
        }
        return START_NOT_STICKY;
    }

    private void startCast(Intent intent) throws UnknownHostException {
        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        InetAddress host = InetAddress.getByAddress(intent.getByteArrayExtra(EXTRA_DEVICE_ADDRESS));
        final int port = intent.getIntExtra(EXTRA_DEVICE_PORT, 0);
        final double startSeconds = intent.getDoubleExtra(EXTRA_START_SECONDS, 0);

        // Another video on the same device is loaded into the running receiver app, which then does not
        // close and show its splash screen again.
        CastConnection current = connection;
        Thread currentWorker = worker;
        if (current != null && currentWorker != null && currentWorker.isAlive() && !current.isClosed()
                && host.equals(castHost) && port == castPort) {
            Logger.printDebug(() -> "Casting " + videoId + " on the running receiver");
            nextVideo.set(new Video(videoId, startSeconds));
            current.stopReading();
            return;
        }

        // A new cast replaces the previous one.
        closeCast(true);
        running = true;
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
        castHost = host;
        castPort = port;

        Notification notification = buildProgressNotification(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.acquire(MAX_CAST_MILLISECONDS);

        Thread thread = new Thread(() -> runCast(new Video(videoId, startSeconds), host, port), "Morphe cast");
        worker = thread;
        thread.start();
    }

    private void runCast(Video first, InetAddress host, int port) {
        try {
            @Nullable CastConnection castConnection = null;
            @Nullable CastStreamServer streamServer = null;
            @Nullable Video video = first;
            for (int load = 0; video != null; load++) {
                CastStreams streams = CastStreams.fetch(video.id());
                if (castConnection == null) {
                    castConnection = new CastConnection(host, port);
                    connection = castConnection;
                    castConnection.launch();

                    streamServer = new CastStreamServer(castConnection.getLocalAddress(), host);
                    server = streamServer;
                }

                // Each video has its own urls, so the receiver does not reuse what it has of the previous one.
                @Nullable CastConnection.Listener listener = loadVideo(castConnection, streamServer, streams,
                        video, "video" + load + "/");
                if (listener == null) {
                    // A video asked for during the load replaces the video that was loading.
                    @Nullable Video next = castConnection.isClosed() ? null : nextVideo.getAndSet(null);
                    if (next != null) {
                        video = next;
                        continue;
                    }
                    if (!Thread.currentThread().isInterrupted()) {
                        Utils.showToastLong(str("morphe_cast_ad_free_failed", deviceName));
                    }
                    return;
                }
                castConnection.readUntilClosed(listener);
                video = castConnection.isClosed() ? null : nextVideo.getAndSet(null);
            }
        } catch (CastStreams.NoStreamsException ex) {
            Logger.printInfo(() -> "Video cannot be cast: " + ex.getMessage());
            Utils.showToastLong(str("morphe_cast_ad_free_no_streams"));
        } catch (IOException | JSONException ex) {
            if (!Thread.currentThread().isInterrupted()) {
                Logger.printException(() -> "Cast failed", ex);
                Utils.showToastLong(str("morphe_cast_ad_free_failed", deviceName));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "runCast failure", ex);
        } finally {
            if (worker == Thread.currentThread()) {
                Utils.runOnMainThread(() -> stopCast(false));
            }
        }
    }

    /**
     * Loads the video and waits until it plays.
     *
     * @param pathPrefix Prefix of the urls of the video.
     * @return The listener of the playing video, or null if the receiver does not play it,
     *         or if another video was asked for in the meantime.
     */
    @Nullable
    private CastConnection.Listener loadVideo(CastConnection castConnection, CastStreamServer streamServer,
                                              CastStreams streams, Video video, String pathPrefix)
            throws IOException, JSONException {
        final int maxHeight = Settings.CAST_AD_FREE_QUALITY.get().maxHeight;
        streamServer.clear();
        for (CastStreams.Stream stream : streams.streams) {
            streamServer.put(pathPrefix + CastStreamServer.streamPath(stream.itag),
                    CastStreamServer.stream(stream, streams.userAgent));
        }
        List<CastConnection.TextTrack> textTracks = new ArrayList<>();
        for (int i = 0; i < streams.captions.size(); i++) {
            CastStreams.Captions captions = streams.captions.get(i);
            String path = pathPrefix + CastStreamServer.captionsPath(i);
            streamServer.put(path, CastStreamServer.captions(captions.url(), streams.userAgent));
            textTracks.add(new CastConnection.TextTrack(captionsTrackId(i), streamServer.getUrl(path),
                    captions.languageCode()));
        }
        castStreams = streams;
        // The manifest names the streams by paths relative to itself.
        streamServer.put(pathPrefix + CastStreamServer.MANIFEST_PATH, CastStreamServer.bytes(
                streams.dashManifest(maxHeight, Settings.CAST_AD_FREE_CODECS.get()).getBytes(StandardCharsets.UTF_8),
                DASH_CONTENT_TYPE));

        CastMediaSession previousSession = mediaSession;
        if (previousSession != null) {
            previousSession.release();
        }
        CastMediaSession session = new CastMediaSession(this, streams.title, createControls());
        mediaSession = session;
        session.setVolume(castConnection.getVolumeLevel());
        paused = false;
        CastConnection.Listener listener = createListener(castConnection, streams.title);
        CastConnection.Media media = new CastConnection.Media(streams.title, thumbnailUrl(video.id()),
                video.startSeconds(), textTracks, captionsTrackId(streams.captionsIndex(captionsVssId)));

        // DASH plays the qualities of one codec, and adapts to the network.
        // Receivers without a DASH player get one file instead.
        boolean playing = castConnection.loadAndWait(streamServer.getUrl(pathPrefix + CastStreamServer.MANIFEST_PATH),
                DASH_CONTENT_TYPE, media, listener);
        @Nullable CastStreams.Stream videoStream = streams.progressiveVideo(maxHeight);
        @Nullable CastStreams.Stream audio = streams.progressiveAudio();
        if (!playing && nextVideo.get() != null) {
            return null;
        }
        if (!playing && videoStream != null && audio != null) {
            Logger.printDebug(() -> "Receiver did not play DASH, casting a single file");
            streamServer.put(pathPrefix + CastStreamServer.MP4_PATH,
                    new VirtualMp4(videoStream, audio, streams.userAgent, streams.durationSeconds));
            playing = castConnection.loadAndWait(streamServer.getUrl(pathPrefix + CastStreamServer.MP4_PATH),
                    MP4_CONTENT_TYPE, media, listener);

            if (!playing && nextVideo.get() != null) {
                return null;
            }
            if (!playing && !Thread.currentThread().isInterrupted()) {
                Logger.printDebug(() -> "Receiver did not play a fragmented file, downloading the video");
                File file;
                try {
                    file = CastMediaFile.create(streams, videoStream, audio,
                            new File(getCacheDir(), CAST_FOLDER), this::showProgress,
                            () -> nextVideo.get() != null);
                } catch (CastMediaFile.CancelledException ex) {
                    return null;
                }
                streamServer.put(pathPrefix + CastStreamServer.FILE_PATH,
                        CastStreamServer.file(file, MP4_CONTENT_TYPE));
                playing = castConnection.loadAndWait(streamServer.getUrl(pathPrefix + CastStreamServer.FILE_PATH),
                        MP4_CONTENT_TYPE, media, listener);
            }
        }
        if (!playing) {
            return null;
        }
        showPlaybackNotification(streams.title);
        return listener;
    }

    private CastConnection.Listener createListener(CastConnection castConnection, String title) {
        return new CastConnection.Listener() {
            private boolean started;

            @Override
            public void onMediaStatus(JSONObject status) {
                CastMediaSession session = mediaSession;
                if (session != null) {
                    session.update(status);
                }
                CastMenuLounge.onMediaStatus(status);
                String state = status.optString("playerState");
                if (state.equals("PLAYING") || state.equals("PAUSED")) {
                    started = true;
                }
                if (started && state.equals("IDLE") && status.has("idleReason")) {
                    final String idleReason = status.optString("idleReason");
                    // A session of the Cast menu stays connected, so the app can cast the next video.
                    if (idleReason.equals("FINISHED") && CastMenuLounge.isConnected()) {
                        Logger.printDebug(() -> "Video finished, waiting for the next video");
                        started = false;
                        return;
                    }
                    // Finished, stopped from the device, or failed to play.
                    Logger.printDebug(() -> "Cast ended: " + idleReason);
                    castConnection.close();
                    return;
                }
                final boolean nowPaused = state.equals("PAUSED");
                if (started && nowPaused != paused) {
                    paused = nowPaused;
                    showPlaybackNotification(title);
                }
            }

            @Override
            public void onLoadFailed() {
                Utils.showToastLong(str("morphe_cast_ad_free_failed", deviceName));
            }

            @Override
            public void onClosed() {
            }
        };
    }

    private CastMediaSession.Controls createControls() {
        return new CastMediaSession.Controls() {
            @Override
            public void play() {
                setPaused(false);
            }

            @Override
            public void pause() {
                setPaused(true);
            }

            @Override
            public void seek(double seconds) {
                sendCommand(castConnection -> castConnection.seek(seconds));
            }

            @Override
            public void stop() {
                stopCast(true);
            }

            @Override
            public void setVolume(double level) {
                sendCommand(castConnection -> castConnection.setVolume(level));
            }
        };
    }

    interface Command {
        void send(CastConnection castConnection) throws IOException, JSONException;
    }

    private void sendCommand(Command command) {
        CastConnection castConnection = connection;
        if (castConnection == null) {
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                command.send(castConnection);
            } catch (IOException | JSONException ex) {
                Logger.printException(() -> "Could not send cast command", ex);
            }
        });
    }

    private void setPaused(boolean pause) {
        sendCommand(pause ? CastConnection::pause : CastConnection::play);
    }

    /**
     * @param stopReceiverApp If the receiver app is closed, which returns the device to its idle screen.
     */
    private void stopCast(boolean stopReceiverApp) {
        closeCast(stopReceiverApp);
        CastMenuLounge.onCastEnded();
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeCast(boolean stopReceiverApp) {
        nextVideo.set(null);
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }

        CastConnection castConnection = connection;
        connection = null;
        if (castConnection != null) {
            Utils.runOnBackgroundThread(() -> {
                if (stopReceiverApp) {
                    try {
                        castConnection.stopReceiverApp();
                    } catch (IOException | JSONException ex) {
                        Logger.printInfo(() -> "Could not stop the receiver app", ex);
                    }
                }
                castConnection.close();
            });
        }

        CastMediaSession session = mediaSession;
        mediaSession = null;
        if (session != null) {
            session.release();
        }

        CastStreamServer mediaServer = server;
        server = null;
        if (mediaServer != null) {
            try {
                mediaServer.close();
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not close the cast server", ex);
            }
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        paused = false;
        lastProgress = -1;

        File folder = new File(getCacheDir(), CAST_FOLDER);
        Utils.runOnBackgroundThread(() -> CastMediaFile.deleteFiles(folder));
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (running) {
            closeCast(true);
            running = false;
        }
        super.onDestroy();
    }

    private static String thumbnailUrl(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // The app declares the notification permission. Without it the notification is not shown,
    // and the cast still works.
    @SuppressLint("NotificationPermission")
    private void showProgress(float fraction) {
        final int progress = (int) (fraction * PROGRESS_MAX);
        if (progress == lastProgress) {
            return;
        }
        lastProgress = progress;
        getManager().notify(NOTIFICATION_ID, buildProgressNotification(progress));
    }

    private Notification buildProgressNotification(int progress) {
        return createBuilder()
                .setContentTitle(str("morphe_cast_ad_free_preparing", deviceName))
                .setProgress(PROGRESS_MAX, progress, progress <= 0)
                .addAction(createAction(ACTION_STOP, str("morphe_cast_ad_free_stop")))
                .build();
    }

    @SuppressLint("NotificationPermission")
    private void showPlaybackNotification(String title) {
        Notification.Builder builder = createBuilder()
                .setContentTitle(title)
                .setContentText(str("morphe_cast_ad_free_casting", deviceName))
                .addAction(paused
                        ? createAction(ACTION_PLAY, str("morphe_cast_ad_free_play"))
                        : createAction(ACTION_PAUSE, str("morphe_cast_ad_free_pause")))
                .addAction(createAction(ACTION_STOP, str("morphe_cast_ad_free_stop")));
        CastMediaSession session = mediaSession;
        if (session != null) {
            // The system shows the media controls of the session, with a seek bar.
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getToken())
                    .setShowActionsInCompactView(0, 1));
        }
        getManager().notify(NOTIFICATION_ID, builder.build());
    }

    private Notification.Builder createBuilder() {
        createChannel();
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(NOTIFICATION_ICON)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
    }

    private Notification.Action createAction(String action, String title) {
        PendingIntent intent = PendingIntent.getService(this, action.hashCode(),
                new Intent(this, CastAdFreeService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, title, intent).build();
    }

    private NotificationManager getManager() {
        return getSystemService(NotificationManager.class);
    }

    private void createChannel() {
        NotificationManager manager = getManager();
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,
                    str("morphe_cast_ad_free_notification_channel"),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }
}
