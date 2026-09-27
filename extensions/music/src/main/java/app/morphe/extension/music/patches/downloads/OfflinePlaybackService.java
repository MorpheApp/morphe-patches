/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.audiofx.AudioEffect;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.shared.Logger;

/** Foreground offline player with audio focus, MediaSession and lock-screen controls. */
public final class OfflinePlaybackService extends Service {
    public interface PlaybackListener {
        void onPlaybackChanged(String path, String title, boolean playing, int position, int duration);
    }

    private static final Set<PlaybackListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile String currentPath = "";
    private static volatile String currentTitle = "";
    private static volatile boolean currentPlaying;
    private static volatile int currentPosition;
    private static volatile int currentDuration;
    private static volatile OfflinePlaybackService instance;

    public static void addListener(PlaybackListener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
    }

    public static void removeListener(PlaybackListener listener) { listeners.remove(listener); }

    public static void toggle(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_TOGGLE));
    }

    public static void skipNext(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_NEXT));
    }
    public static void skipPrevious(Context context) {
        context.startService(new Intent(context, OfflinePlaybackService.class).setAction(ACTION_PREVIOUS));
    }

    public static void seekTo(int position) {
        OfflinePlaybackService service = instance;
        if (service != null && service.playerReady()) {
            service.player.seekTo(Math.max(0, Math.min(position, service.player.getDuration())));
            service.publishState();
        }
    }
    public static final String ACTION_PLAY_FILE = "app.morphe.action.PLAY_OFFLINE_FILE";
    public static final String ACTION_TOGGLE = "app.morphe.action.TOGGLE_OFFLINE_PLAYBACK";
    public static final String ACTION_STOP = "app.morphe.action.STOP_OFFLINE_PLAYBACK";
    public static final String ACTION_NEXT = "app.morphe.action.NEXT_OFFLINE_TRACK";
    public static final String ACTION_PREVIOUS = "app.morphe.action.PREVIOUS_OFFLINE_TRACK";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_QUEUE = "queue";
    public static final String EXTRA_QUEUE_INDEX = "queue_index";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";
    public static final String EXTRA_ARTWORK_PATH = "artwork_path";

    private static final String CHANNEL_ID = "morphe_offline_playback";
    private static final int NOTIFICATION_ID = 8841;

    private MediaPlayer player;
    private boolean prepared;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int audioEffectSessionId;
    private String path = "";
    private String publishedMetadataPath = "";
    private String title = "YouTube Music";
    private String artist = "YouTube Music";
    private Bitmap artwork;
    private ArrayList<String> queue = new ArrayList<>();
    private int queueIndex = -1;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            publishState();
            if (player != null) progressHandler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createFocusRequest();
        createChannel();
        session = new MediaSession(this, "MorpheOfflinePlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playQueueOffset(1); }
            @Override public void onSkipToPrevious() { playQueueOffset(-1); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) {
                if (playerReady()) player.seekTo((int) Math.min(Integer.MAX_VALUE, pos));
                publishState();
            }
        });
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) session.setSessionActivity(PendingIntent.getActivity(this, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY_FILE.equals(action)) {
            ArrayList<String> suppliedQueue = intent.getStringArrayListExtra(EXTRA_QUEUE);
            if (suppliedQueue != null) {
                queue = suppliedQueue;
                queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1);
            }
            playFile(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_TITLE),
                    intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_ARTWORK_PATH));
        } else if (ACTION_TOGGLE.equals(action)) {
            if (playerReady() && player.isPlaying()) pause(); else resume();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_NEXT.equals(action)) {
            playQueueOffset(1);
        } else if (ACTION_PREVIOUS.equals(action)) {
            playQueueOffset(-1);
        }
        return START_NOT_STICKY;
    }

    private void playFile(@Nullable String path, @Nullable String requestedTitle,
                          @Nullable String requestedArtist, @Nullable String artworkPath) {
        if (path == null || !new File(path).isFile()) return;
        this.path = path;
        title = requestedTitle == null ? new File(path).getName() : requestedTitle;
        artist = requestedArtist == null || requestedArtist.isBlank() ? "YouTube Music" : requestedArtist;
        // A track saved without a cover has no file, and decoding it would log an error a second.
        File artworkFile = artworkPath == null ? null : new File(artworkPath);
        artwork = artworkFile != null && artworkFile.isFile()
                ? BitmapFactory.decodeFile(artworkPath)
                : null;
        releasePlayer();
        try {
            pauseOtherMedia();
            if (audioFocusDenied()) return;
            player = new MediaPlayer();
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            // Skipping quickly leaves the previous player mid prepare. Its callbacks are
            // cleared on release, and each one still checks that it belongs to the current
            // track, so a discarded player can never tear down the one that replaced it.
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                openAudioEffectSession(mp);
                mp.start();
                publishState();
                startForeground(NOTIFICATION_ID, notification());
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
            });
            player.setOnCompletionListener(mp -> {
                if (mp != player) return;
                if (queueIndex >= 0 && queueIndex + 1 < queue.size()) playQueueOffset(1);
                else stopPlayback();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                Logger.printException(() -> "Offline player error: " + what + "/" + extra);
                stopPlayback();
                return true;
            });
            player.prepareAsync();
            startForeground(NOTIFICATION_ID, notification());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start offline playback", ex);
            stopPlayback();
        }
    }

    private void playQueueOffset(int offset) {
        int target = queueIndex + offset;
        if (target < 0 || target >= queue.size()) return;
        queueIndex = target;
        OfflineTrack track = OfflineTrack.load(new File(queue.get(target)));
        playFile(track.audioFile().getAbsolutePath(), track.displayTitle(), track.displayArtist(),
                track.artworkFile().getAbsolutePath());
    }

    private void resume() {
        if (!playerReady()) return;
        if (audioFocusDenied()) return;
        player.start();
        publishState();
        notifyChanged();
    }

    private void pause() {
        if (!playerReady() || !player.isPlaying()) return;
        player.pause();
        publishState();
        notifyChanged();
    }

    private void stopPlayback() {
        releasePlayer();
        progressHandler.removeCallbacks(progressTicker);
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        publishedMetadataPath = "";
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(
                    PlaybackState.STATE_STOPPED, 0, 0).build());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Asking a player that is still preparing about its state is an invalid operation, which it
     * answers with an error that stops playback, so nothing may reach it before it is ready.
     */
    private boolean playerReady() {
        return prepared && player != null;
    }

    private void openAudioEffectSession(MediaPlayer mediaPlayer) {
        int sessionId = mediaPlayer.getAudioSessionId();
        if (sessionId <= 0) return;

        audioEffectSessionId = sessionId;
        sendBroadcast(new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC));
    }

    private void closeAudioEffectSession() {
        int sessionId = audioEffectSessionId;
        if (sessionId <= 0) return;

        audioEffectSessionId = 0;
        sendBroadcast(new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }

    private void releasePlayer() {
        prepared = false;
        progressHandler.removeCallbacks(progressTicker);
        closeAudioEffectSession();

        MediaPlayer released = player;
        if (released == null) return;

        // Cleared first, otherwise releasing a player that is still preparing reports an error
        // that would stop whatever is playing by then.
        player = null;
        released.setOnPreparedListener(null);
        released.setOnCompletionListener(null);
        released.setOnErrorListener(null);
        released.reset();
        released.release();
    }

    private void createFocusRequest() {
        if (audioManager == null) return;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pause();
                    }
                })
                .build();
    }

    /**
     * @return Whether the system refused audio focus, so playback must not start.
     */
    private boolean audioFocusDenied() {
        return audioManager == null || focusRequest == null
                || audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Pause the currently routed system player before this session becomes active. */
    private void pauseOtherMedia() {
        if (audioManager == null || !audioManager.isMusicActive()) return;
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void publishState() {
        MediaSession currentSession = session;
        if (currentSession == null) return;

        final boolean playing = playerReady() && player.isPlaying();
        final long position = playerReady() ? player.getCurrentPosition() : 0;
        currentSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
                .build());
        currentPath = path == null ? "" : path;
        currentTitle = title;
        currentPlaying = playing;
        currentPosition = (int) position;
        currentDuration = playerReady() ? player.getDuration() : 0;
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged(currentPath, currentTitle, currentPlaying, currentPosition, currentDuration);
        }
        // Rebuilding the metadata copies the artwork, so only do it when the track changed.
        if (!currentPath.equals(publishedMetadataPath)) {
            publishedMetadataPath = currentPath;
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDuration);
            if (artwork != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                        .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
            }
            currentSession.setMetadata(metadata.build());
        }
    }

    @SuppressLint("WrongConstant")
    private Notification notification() {
        final boolean playing = playerReady() && player.isPlaying();
        PendingIntent toggle = serviceIntent(ACTION_TOGGLE, 1);
        PendingIntent stop = serviceIntent(ACTION_STOP, 2);
        PendingIntent previous = serviceIntent(ACTION_PREVIOUS, 4);
        PendingIntent next = serviceIntent(ACTION_NEXT, 5);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = PendingIntent.getActivity(this, 3, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(artwork)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .addAction(action(android.R.drawable.ic_media_previous,
                        str("morphe_music_downloads_previous"), previous))
                .addAction(action(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        str(playing ? "morphe_music_downloads_pause" : "morphe_music_downloads_play"),
                        toggle))
                .addAction(action(android.R.drawable.ic_media_next,
                        str("morphe_music_downloads_next"), next))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
                        str("morphe_music_downloads_close"), stop))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private Notification.Action action(int iconResource, String title, PendingIntent intent) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, iconResource), title, intent).build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent intent = new Intent(this, OfflinePlaybackService.class).setAction(action);
        //noinspection WrongConstant
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressLint("NotificationPermission") // The patch declares POST_NOTIFICATIONS.
    private void notifyChanged() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private void createChannel() {
        //noinspection WrongConstant
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                str("morphe_music_downloads_playback_channel"), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        progressHandler.removeCallbacks(progressTicker);
        releasePlayer();
        artwork = null;
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        if (session != null) { session.release(); session = null; }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
