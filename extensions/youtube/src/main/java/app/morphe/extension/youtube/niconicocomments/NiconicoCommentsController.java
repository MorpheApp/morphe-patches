package app.morphe.extension.youtube.niconicocomments;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/** Bridge between Morphe's shared player hooks, chat networking, and the overlay. */
public final class NiconicoCommentsController {
    private static final String PREFERENCES_NAME = "niconico_comments";
    private static final String ENABLED_KEY = "overlay_enabled";
    private static final String FONT_SIZE_KEY = "font_size_sp";
    private static final String OPACITY_KEY = "opacity_percent";
    private static final String MIGRATED_KEY = "morphe_niconico_settings_migrated";
    private static final long SEEK_THRESHOLD_MS = 12_000;
    private static final Object SOURCE_LOCK = new Object();
    private static final Object VIDEO_CLOCK_LOCK = new Object();

    static final int DEFAULT_FONT_SIZE_SP = 20;
    static final int MIN_FONT_SIZE_SP = 12;
    static final int MAX_FONT_SIZE_SP = 40;
    static final int DEFAULT_OPACITY_PERCENT = 100;
    static final int MIN_OPACITY_PERCENT = 10;
    static final int MAX_OPACITY_PERCENT = 100;

    private static WeakReference<ViewGroup> overlayRootRef = new WeakReference<>(null);
    private static WeakReference<NiconicoCommentsOverlayView> overlayViewRef =
            new WeakReference<>(null);

    private static volatile String currentVideoId = "";
    private static volatile boolean playerVisible;
    private static volatile boolean videoPlaying;
    private static volatile boolean commentsEnabled = true;
    private static volatile int commentFontSizeSp = DEFAULT_FONT_SIZE_SP;
    private static volatile int commentOpacityPercent = DEFAULT_OPACITY_PERCENT;
    private static volatile boolean preferenceLoaded;
    // Accessed only on the main thread while attaching the overlay.
    private static boolean playerStateObserversRegistered;
    private static volatile long sampledVideoTimeMs = -1;
    private static volatile long sampleElapsedRealtimeMs;
    private static volatile double playbackRate = 1.0;

    private static YoutubeChatSource activeSource;
    private static Thread activeSourceThread;
    private static String completedSourceVideoId = "";

    private static final YoutubeChatSource.Listener CHAT_LISTENER =
            new YoutubeChatSource.Listener() {
                @Override
                public long getVideoTimeMs() {
                    return getVideoTime();
                }

                @Override
                public void onComments(
                        String videoId,
                        List<NiconicoComment> comments,
                        long liveSpreadWindowMs
                ) {
                    if (!commentsEnabled || !videoId.equals(currentVideoId)) {
                        return;
                    }
                    NiconicoCommentsOverlayView view = overlayViewRef.get();
                    if (view != null) {
                        view.post(() -> {
                            if (commentsEnabled && videoId.equals(currentVideoId)) {
                                view.addComments(comments, liveSpreadWindowMs);
                            }
                        });
                    }
                }

                @Override
                public void onReplayReset(String videoId) {
                    if (!videoId.equals(currentVideoId)) {
                        return;
                    }
                    NiconicoCommentsOverlayView view = overlayViewRef.get();
                    if (view != null) {
                        view.post(view::clearComments);
                    }
                }
            };

    private NiconicoCommentsController() {
    }

    /** Injection point called when YouTube creates its player overlay. */
    public static void initialize(ViewGroup overlayRoot) {
        if (overlayRoot == null) {
            return;
        }
        loadPreference(overlayRoot.getContext());
        overlayRoot.post(() -> {
            try {
                if (!playerStateObserversRegistered) {
                    playerStateObserversRegistered = true;
                    PlayerType.getOnChange().addObserver(NiconicoCommentsController::setPlayerType);
                    VideoState.getOnChange().addObserver(NiconicoCommentsController::setVideoState);
                }
                setPlayerType(PlayerType.getCurrent());
                setVideoState(VideoState.getCurrent());
                attachToOverlay(overlayRoot);
                maybeStartChatSource();
            } catch (Exception exception) {
                Logger.printException(() -> "Niconico overlay initialization failed", exception);
            }
        });
    }

    /** Injection point called when the foreground video changes. */
    public static void onVideoIdChanged(String videoId) {
        String nextVideoId = videoId == null ? "" : videoId;
        if (nextVideoId.equals(currentVideoId)) {
            return;
        }

        currentVideoId = nextVideoId;
        synchronized (VIDEO_CLOCK_LOCK) {
            sampledVideoTimeMs = -1;
            sampleElapsedRealtimeMs = SystemClock.elapsedRealtime();
        }
        synchronized (SOURCE_LOCK) {
            completedSourceVideoId = "";
            stopChatSourceLocked();
        }

        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            view.post(() -> {
                view.setVideoId(nextVideoId);
                applyViewState(view);
                maybeStartChatSource();
            });
        }
    }

    /** Injection point receiving YouTube's current player presentation state. */
    public static void setPlayerType(Enum<?> playerType) {
        String name = playerType == null ? "" : playerType.name();
        playerVisible = "WATCH_WHILE_MAXIMIZED".equals(name)
                || "WATCH_WHILE_FULLSCREEN".equals(name)
                || "WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN".equals(name);
        updateViewState();
    }

    /** Injection point receiving YouTube's play/pause state. */
    public static void setVideoState(Enum<?> videoState) {
        boolean nextPlaying = videoState != null && "PLAYING".equals(videoState.name());
        long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            if (videoPlaying && !nextPlaying) {
                sampledVideoTimeMs = getVideoTimeLocked(nowMs);
            }
            sampleElapsedRealtimeMs = nowMs;
            videoPlaying = nextPlaying;
        }
        updateViewState();
        maybeStartChatSource();
    }

    /** Injection point receiving YouTube's periodic video-time sample. */
    public static void setVideoTime(long videoTimeMs) {
        long nowMs = SystemClock.elapsedRealtime();
        boolean seeked;
        synchronized (VIDEO_CLOCK_LOCK) {
            long predictedVideoTimeMs = getVideoTimeLocked(nowMs);
            seeked = predictedVideoTimeMs >= 0
                    && Math.abs(videoTimeMs - predictedVideoTimeMs) > SEEK_THRESHOLD_MS;

            sampledVideoTimeMs = videoTimeMs;
            sampleElapsedRealtimeMs = nowMs;
        }
        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            if (seeked) {
                view.post(view::clearComments);
            } else {
                view.postInvalidateOnAnimation();
            }
        }
    }

    /** Injection point receiving every playback-rate change applied by YouTube. */
    public static void setPlaybackRate(float nextPlaybackRate) {
        double sanitizedRate = sanitizePlaybackRate(nextPlaybackRate);
        long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            if (sampledVideoTimeMs >= 0 && videoPlaying) {
                sampledVideoTimeMs = getVideoTimeLocked(nowMs);
            }
            sampleElapsedRealtimeMs = nowMs;
            playbackRate = sanitizedRate;
        }

        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            view.postInvalidateOnAnimation();
        }
    }

    static long getVideoTime() {
        long nowMs = SystemClock.elapsedRealtime();
        synchronized (VIDEO_CLOCK_LOCK) {
            return getVideoTimeLocked(nowMs);
        }
    }

    private static long getVideoTimeLocked(long nowMs) {
        long sampledTime = sampledVideoTimeMs;
        if (sampledTime < 0 || !videoPlaying) {
            return sampledTime;
        }
        return interpolateVideoTime(
                sampledTime,
                Math.max(0, nowMs - sampleElapsedRealtimeMs),
                playbackRate
        );
    }

    static long interpolateVideoTime(long sampledTimeMs, long elapsedRealtimeMs, double rate) {
        if (elapsedRealtimeMs <= 0) {
            return sampledTimeMs;
        }

        double advancedVideoTimeMs = elapsedRealtimeMs * sanitizePlaybackRate(rate);
        if (advancedVideoTimeMs >= Long.MAX_VALUE - sampledTimeMs) {
            return Long.MAX_VALUE;
        }
        return sampledTimeMs + Math.round(advancedVideoTimeMs);
    }

    static double sanitizePlaybackRate(double rate) {
        return rate > 0.0 && !Double.isNaN(rate) && !Double.isInfinite(rate) ? rate : 1.0;
    }

    static boolean isCommentsEnabled() {
        return commentsEnabled;
    }

    static boolean toggleCommentsEnabled(Context context) {
        loadPreference(context);
        boolean enabled = !commentsEnabled;
        Settings.NICONICO_COMMENTS_ENABLED.save(enabled);
        readPreferences();
        return enabled;
    }

    static int getCommentFontSizeSp(Context context) {
        loadPreference(context);
        return commentFontSizeSp;
    }

    static void setCommentFontSizeSp(Context context, int fontSizeSp) {
        loadPreference(context);
        int clampedSize = clampFontSizeSp(fontSizeSp);
        Settings.NICONICO_COMMENTS_FONT_SIZE.save(clampedSize);
        readPreferences();
    }

    static int clampFontSizeSp(int fontSizeSp) {
        return Math.max(MIN_FONT_SIZE_SP, Math.min(MAX_FONT_SIZE_SP, fontSizeSp));
    }

    static int getCommentOpacityPercent(Context context) {
        loadPreference(context);
        return commentOpacityPercent;
    }

    static void setCommentOpacityPercent(Context context, int opacityPercent) {
        loadPreference(context);
        int clampedOpacity = clampOpacityPercent(opacityPercent);
        Settings.NICONICO_COMMENTS_OPACITY.save(clampedOpacity);
        readPreferences();
    }

    static int clampOpacityPercent(int opacityPercent) {
        return Math.max(
                MIN_OPACITY_PERCENT,
                Math.min(MAX_OPACITY_PERCENT, opacityPercent)
        );
    }

    /** Injection point: migrate settings before the settings UI or player is opened. */
    public static void loadPreference(Context context) {
        if (preferenceLoaded || context == null) {
            return;
        }
        synchronized (SOURCE_LOCK) {
            if (preferenceLoaded) {
                return;
            }
            SharedPreferences preferences = context.getApplicationContext()
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (migratePreferences(preferences, Setting.preferences.preferences)) {
                // Settings may already be loaded; refresh their cached values after migrating.
                Settings.NICONICO_COMMENTS_ENABLED.save(Setting.preferences.getBoolean(
                        Settings.NICONICO_COMMENTS_ENABLED.key, Settings.NICONICO_COMMENTS_ENABLED.get()));
                Settings.NICONICO_COMMENTS_FONT_SIZE.save(Setting.preferences.getIntegerString(
                        Settings.NICONICO_COMMENTS_FONT_SIZE.key, Settings.NICONICO_COMMENTS_FONT_SIZE.get()));
                Settings.NICONICO_COMMENTS_OPACITY.save(Setting.preferences.getIntegerString(
                        Settings.NICONICO_COMMENTS_OPACITY.key, Settings.NICONICO_COMMENTS_OPACITY.get()));
            }
            Setting.addImportExportCallback(new Setting.ImportExportCallback() {
                @Override
                public void settingsImported(Activity activity) {
                    readPreferences();
                }

                @Override
                public void settingsExported(Activity activity) {
                }
            });
            preferenceLoaded = true;
            readPreferences();
        }
    }

    static boolean migratePreferences(SharedPreferences legacy, SharedPreferences current) {
        if (current.getBoolean(MIGRATED_KEY, false)) return false;

        Map<String, ?> values = legacy.getAll();
        SharedPreferences.Editor editor = current.edit();
        String enabledKey = "morphe_niconico_comments_enabled";
        String fontSizeKey = "morphe_niconico_comments_font_size";
        String opacityKey = "morphe_niconico_comments_opacity";
        if (!current.contains(enabledKey) && values.get(ENABLED_KEY) instanceof Boolean value) {
            editor.putBoolean(enabledKey, value);
        }
        // IntegerSetting stores integers as strings in the common preference file.
        if (!current.contains(fontSizeKey) && values.get(FONT_SIZE_KEY) instanceof Integer value) {
            editor.putString(fontSizeKey, Integer.toString(clampFontSizeSp(value)));
        }
        if (!current.contains(opacityKey) && values.get(OPACITY_KEY) instanceof Integer value) {
            editor.putString(opacityKey, Integer.toString(clampOpacityPercent(value)));
        }
        // Write the marker with the migrated values; leave legacy data available for downgrades.
        editor.putBoolean(MIGRATED_KEY, true).apply();
        return true;
    }

    private static void readPreferences() {
        boolean wasEnabled = commentsEnabled;
        commentsEnabled = Settings.NICONICO_COMMENTS_ENABLED.get();
        commentFontSizeSp = clampFontSizeSp(Settings.NICONICO_COMMENTS_FONT_SIZE.get());
        commentOpacityPercent = clampOpacityPercent(Settings.NICONICO_COMMENTS_OPACITY.get());
        if (commentFontSizeSp != Settings.NICONICO_COMMENTS_FONT_SIZE.get()) {
            Settings.NICONICO_COMMENTS_FONT_SIZE.save(commentFontSizeSp);
        }
        if (commentOpacityPercent != Settings.NICONICO_COMMENTS_OPACITY.get()) {
            Settings.NICONICO_COMMENTS_OPACITY.save(commentOpacityPercent);
        }
        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            view.post(() -> {
                if (!commentsEnabled) view.clearComments();
                applyViewState(view);
            });
        }
        NiconicoCommentsToggleButton.refreshState();
        synchronized (SOURCE_LOCK) {
            if (wasEnabled != commentsEnabled) {
                if (commentsEnabled) completedSourceVideoId = "";
                else stopChatSourceLocked();
            }
        }
        if (commentsEnabled) maybeStartChatSource();
    }

    private static void attachToOverlay(ViewGroup overlayRoot) {
        NiconicoCommentsOverlayView currentView = overlayViewRef.get();
        ViewGroup currentRoot = overlayRootRef.get();
        if (currentRoot == overlayRoot && currentView != null && currentView.getParent() == overlayRoot) {
            currentView.setVideoId(currentVideoId);
            applyViewState(currentView);
            return;
        }

        NiconicoCommentsOverlayView newView = currentView;
        if (newView != null && newView.getParent() instanceof ViewGroup) {
            ((ViewGroup) newView.getParent()).removeView(newView);
        }
        if (newView == null) {
            newView = new NiconicoCommentsOverlayView(overlayRoot.getContext());
        }
        newView.setVideoId(currentVideoId);
        newView.setCommentFontSizeSp(commentFontSizeSp);
        newView.setCommentOpacityPercent(commentOpacityPercent);
        newView.setClickable(false);
        newView.setFocusable(false);
        newView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlayRoot.addView(
                newView,
                0,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        overlayRootRef = new WeakReference<>(overlayRoot);
        overlayViewRef = new WeakReference<>(newView);
        applyViewState(newView);
    }

    private static void maybeStartChatSource() {
        if (!commentsEnabled || currentVideoId.isEmpty() || overlayRootRef.get() == null) {
            return;
        }

        synchronized (SOURCE_LOCK) {
            if (currentVideoId.equals(completedSourceVideoId)) {
                return;
            }
            if (activeSourceThread != null && activeSourceThread.isAlive()) {
                return;
            }

            String sourceVideoId = currentVideoId;
            YoutubeChatSource source = new YoutubeChatSource(sourceVideoId, CHAT_LISTENER);
            Thread thread = new Thread(() -> {
                try {
                    source.run();
                } finally {
                    synchronized (SOURCE_LOCK) {
                        if (activeSource == source) {
                            activeSource = null;
                            activeSourceThread = null;
                            completedSourceVideoId = sourceVideoId;
                        }
                    }
                }
            }, "NiconicoYoutubeChat");
            thread.setDaemon(true);
            activeSource = source;
            activeSourceThread = thread;
            thread.start();
        }
    }

    private static void stopChatSourceLocked() {
        YoutubeChatSource source = activeSource;
        Thread thread = activeSourceThread;
        activeSource = null;
        activeSourceThread = null;
        if (source != null) {
            source.stop();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void updateViewState() {
        NiconicoCommentsOverlayView view = overlayViewRef.get();
        if (view != null) {
            view.post(() -> applyViewState(view));
        }
    }

    private static void applyViewState(NiconicoCommentsOverlayView view) {
        view.setCommentFontSizeSp(commentFontSizeSp);
        view.setCommentOpacityPercent(commentOpacityPercent);
        view.setOverlayActive(commentsEnabled && playerVisible && !currentVideoId.isEmpty());
        view.setVideoPlaying(videoPlaying);
    }
}
