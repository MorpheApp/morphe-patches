package app.morphe.extension.music.patches.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import app.morphe.extension.shared.Logger;

/** Foreground offline player with audio focus, MediaSession and lock-screen controls. */
public final class OfflinePlaybackService extends Service {
    public interface PlaybackListener {
        void onPlaybackChanged(String title, boolean playing, int position, int duration);
    }

    private static final Set<PlaybackListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile String currentTitle = "";
    private static volatile boolean currentPlaying;
    private static volatile int currentPosition;
    private static volatile int currentDuration;
    private static volatile OfflinePlaybackService instance;

    public static void addListener(PlaybackListener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(currentTitle, currentPlaying, currentPosition, currentDuration);
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
        if (service != null && service.player != null) {
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
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
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
                if (player != null) player.seekTo((int) Math.min(Integer.MAX_VALUE, pos));
                publishState();
            }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
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
            if (player != null && player.isPlaying()) pause(); else resume();
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
        title = requestedTitle == null ? new File(path).getName() : requestedTitle;
        artist = requestedArtist == null || requestedArtist.isBlank() ? "YouTube Music" : requestedArtist;
        artwork = artworkPath == null ? null : BitmapFactory.decodeFile(artworkPath);
        releasePlayer();
        try {
            pauseOtherMedia();
            if (!requestFocus()) return;
            player = new MediaPlayer();
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                mp.start();
                publishState();
                startForeground(NOTIFICATION_ID, notification());
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
            });
            player.setOnCompletionListener(mp -> {
                if (queueIndex >= 0 && queueIndex + 1 < queue.size()) playQueueOffset(1);
                else stopPlayback();
            });
            player.setOnErrorListener((mp, what, extra) -> {
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
        if (player == null) return;
        if (!requestFocus()) return;
        player.start();
        publishState();
        notifyChanged();
    }

    private void pause() {
        if (player == null || !player.isPlaying()) return;
        player.pause();
        publishState();
        notifyChanged();
    }

    private void stopPlayback() {
        releasePlayer();
        progressHandler.removeCallbacks(progressTicker);
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        session.setPlaybackState(new PlaybackState.Builder().setState(
                PlaybackState.STATE_STOPPED, 0, 0).build());
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            player.reset();
            player.release();
            player = null;
        }
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

    private boolean requestFocus() {
        return audioManager != null && focusRequest != null &&
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
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
        boolean playing = player != null && player.isPlaying();
        long position = player == null ? 0 : player.getCurrentPosition();
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position, playing ? 1f : 0f)
                .build());
        currentTitle = title;
        currentPlaying = playing;
        currentPosition = (int) position;
        currentDuration = player == null ? 0 : player.getDuration();
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackChanged(currentTitle, currentPlaying, currentPosition, currentDuration);
        }
        android.media.MediaMetadata.Builder metadata = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,
                        player == null ? 0 : player.getDuration());
        if (artwork != null) {
            metadata.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, artwork)
                    .putBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(metadata.build());
    }

    private Notification notification() {
        boolean playing = player != null && player.isPlaying();
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
                .addAction(android.R.drawable.ic_media_previous, "Precedente", previous)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausa" : "Riproduci", toggle)
                .addAction(android.R.drawable.ic_media_next, "Successivo", next)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Chiudi", stop)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private PendingIntent serviceIntent(String action, int code) {
        Intent intent = new Intent(this, OfflinePlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notifyChanged() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification());
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Riproduzione offline",
                NotificationManager.IMPORTANCE_LOW);
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
