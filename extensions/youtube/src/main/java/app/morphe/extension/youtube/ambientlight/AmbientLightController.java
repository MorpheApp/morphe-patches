/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4576
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.AmbientModePatch;
import app.morphe.extension.youtube.patches.FeedCardLayoutPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Fills the watch page around the player with the light of the video.
 * <p>
 * Everything is sized for the lowest battery use: a small readback a few times a second,
 * and nothing at all when neither the picture nor the light would change.
 */
@SuppressWarnings("unused")
public final class AmbientLightController {
    private AmbientLightController() {
    }

    // Half float bitmaps are linear before Android 10, and the light is encoded for extended sRGB.
    private static final boolean ENABLED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Settings.ENHANCED_AMBIENT_MODE.get();

    private static final int PICTURE_WIDTH = 48;
    private static final int PICTURE_HEIGHT = 27;
    private static final int READBACK_SCALE = 2;
    private static final int READBACK_WIDTH = PICTURE_WIDTH * READBACK_SCALE;
    private static final int READBACK_HEIGHT = PICTURE_HEIGHT * READBACK_SCALE;
    private static final int FAR_WIDTH = PICTURE_WIDTH;
    private static final int FAR_HEIGHT = 2;
    /** Light pixels across the width of the window. */
    private static final int LIGHT_COLUMNS = 64;
    private static final int LIGHT_CHANNELS = 3;
    private static final int BITMAP_CHANNELS = 4;

    private static final long CAPTURE_INTERVAL_MS = 66;
    private static final long STILL_CAPTURE_INTERVAL_MS = 500;
    /** Failed captures in a row after which capturing slows down, as with DRM protected video. */
    private static final int CAPTURE_FAILURES_BEFORE_BACKOFF = 8;
    private static final long FAILED_CAPTURE_INTERVAL_MS = 1_000;
    /** Smaller changes of the light than this, out of 1, are less than one step of an 8 bit display. */
    private static final float MIN_VISIBLE_CHANGE = 0.4f / 255f;
    private static final int STILL_CAPTURES_BEFORE_SLOWDOWN = 8;
    /** Video time reports stop while paused, and arrive about once a second while playing. */
    private static final long PLAYING_TIMEOUT_MS = 2_000;
    private static final long PAGE_SCAN_INTERVAL_MS = 1_000;
    private static final long RETRY_DELAY_MS = 300;
    private static final int MAX_RETRIES = 20;
    /** Captures the black bars of a video are judged over. */
    private static final int BARS_STABLE_CAPTURES = 30;
    /** Captures that must agree, out of {@link #BARS_STABLE_CAPTURES}. */
    private static final int BARS_MIN_VOTES = 15;
    /** Readback pixels the edge of a picture can move between captures. */
    private static final int BARS_TOLERANCE = 2;
    private static final int MIN_RECT_SIZE = 4;

    /** Strength of the light while the page is being used, out of 256. */
    private static final int DIMMED_STRENGTH = 64;
    private static final int FULL_STRENGTH = 256;
    private static final long DIM_DURATION_MS = 180;
    private static final long RESTORE_DURATION_MS = 1_600;
    private static final long RESTORE_DELAY_MS = 3_000;
    private static final long FADE_STEP_MS = 33;
    /** Share of the way to the wanted cover for each capture, so that scene cuts do not pump. */
    private static final float COVER_EASING = 0.25f;
    private static final float COVER_SETTLED = 0.004f;
    /** Scrolls this soon after a touch on the page are the user's. Other views scroll by themselves. */
    private static final long TOUCH_SCROLL_WINDOW_MS = 3_000;
    private static final long INTERACTION_THROTTLE_MS = 100;
    private static final int[] FULL_READBACK = {0, 0, READBACK_WIDTH, READBACK_HEIGHT};
    private static final int[] FULL_PICTURE = {0, 0, PICTURE_WIDTH, PICTURE_HEIGHT};

    /** Mean channel difference (out of 255) that the light does not need to follow. */
    private static final int UNCHANGED_DIFFERENCE = 1;
    /** Mean channel difference of a scene cut, which the light follows without smoothing. */
    private static final int SCENE_CUT_DIFFERENCE = 40;
    /** Share of the previous light kept for each capture, out of 256. */
    private static final int SMOOTHING_KEEP = 64;

    /** A background this small is a button, and keeps its color. */
    private static final float BUTTON_MAX_WIDTH_SCREEN_RATIO = 0.45f;
    private static final float BUTTON_MAX_HEIGHT_DP = 64f;
    private static final int DARK_PAGE_COLOR = 0xFF0F0F0F;
    private static final int LIGHT_PAGE_COLOR = 0xFFFFFFFF;
    private static final int MAX_PLAYER_ANCESTORS = 8;

    private static final int[] WATCH_PAGE_IDS = {
            ResourceUtils.getIdentifier(ResourceType.ID, "next_gen_watch_layout_no_player_fragment_container"),
            ResourceUtils.getIdentifier(ResourceType.ID, "next_gen_watch_layout"),
            ResourceUtils.getIdentifier(ResourceType.ID, "watch_while_layout"),
    };

    private static final class Region {
        final LightSurface surface;
        final int left;
        final int top;
        final boolean onPage;

        Region(LightSurface surface, int left, int top, boolean onPage) {
            this.surface = surface;
            this.left = left;
            this.top = top;
            this.onPage = onPage;
        }
    }

    /** Immutable snapshot of where the video and the light are. */
    private static final class Layout {
        final int[] video;
        final int windowWidth;
        final int windowHeight;
        final int gridHeight;
        final List<Region> regions;

        Layout(int[] video, int windowWidth, int windowHeight, int gridHeight, List<Region> regions) {
            this.video = video;
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
            this.gridHeight = gridHeight;
            this.regions = regions;
        }
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private static Handler worker;

    private static WeakReference<ViewGroup> overlayRef = new WeakReference<>(null);
    private static WeakReference<View> videoRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> playerRef = new WeakReference<>(null);
    private static WeakReference<View> pageRef = new WeakReference<>(null);
    private static WeakReference<View> rootRef = new WeakReference<>(null);

    private static boolean observerRegistered;
    private static boolean active;
    private static boolean tickScheduled;
    private static volatile boolean captureInFlight;
    private static volatile int captureFailures;
    private static long lastPageScan;
    private static volatile int pageColor;
    /** Set once the first light frame is showing, so the page is never see-through over nothing. */
    private static volatile boolean pageCleared;
    private static int retries;
    @Nullable
    private static int[] lastGeometry;
    private static long lastPageTouch;
    private static long lastInteraction;
    private static long lastFadeStep;
    private static boolean dimmed;
    private static WeakReference<View> touchWatcherRef = new WeakReference<>(null);
    private static final List<LightSurface> pageSurfaces = new ArrayList<>();
    private static final List<LightSurface> playerSurfaces = new ArrayList<>();

    /** The Ambient mode toggle of the player flyout menu, which turns the light off too. */
    private static volatile boolean ambientModeToggle = true;
    private static volatile boolean seeThrough;
    private static volatile int buttonMaxWidth;
    private static volatile int buttonMaxHeight;
    private static volatile long lastVideoTime = -1;
    private static volatile long lastVideoTimeChange;
    /** False while the light still has to reach the current picture. */
    private static volatile boolean settled;
    private static volatile int stillCaptures;
    /** Strength of the light over the page color, out of 256. */
    private static volatile int strength = FULL_STRENGTH;
    /** The part of the video the colors of the light come from, without black bars. In readback pixels. */
    private static volatile int[] readbackBounds = FULL_READBACK;
    @Nullable
    private static volatile Layout layout;

    /** Created on first use, as the hooks load this class even if the light is off. */
    @Nullable
    private static Bitmap readback;
    // Used only on the worker thread.
    private static final int[] readbackPixels = new int[READBACK_WIDTH * READBACK_HEIGHT];
    private static final int[] pictureColors = new int[PICTURE_WIDTH * PICTURE_HEIGHT];
    private static final int[] near = new int[PICTURE_WIDTH * PICTURE_HEIGHT];
    private static final int[] far = new int[FAR_WIDTH * FAR_HEIGHT];
    private static final float[] nearLinear = new float[PICTURE_WIDTH * PICTURE_HEIGHT * LIGHT_CHANNELS];
    private static final float[] farLinear = new float[FAR_WIDTH * FAR_HEIGHT * LIGHT_CHANNELS];
    private static final float[] pageLinear = new float[LIGHT_CHANNELS];
    private static float[] light = new float[0];
    private static float wantedCover;
    private static float cover;
    private static float[] encodedLight = new float[0];
    private static float[] presentedLight = new float[0];
    private static float[] lightSoft = new float[0];
    private static float[] lightScratch = new float[0];
    private static short[] halfFloats = new short[0];
    private static final Paint lightPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    @Nullable
    private static Bitmap lightBitmap;
    @Nullable
    private static int[] barsCandidate;
    private static int barsCaptures;
    private static int barsVotes;
    private static boolean resetSmoothing;
    @Nullable
    private static Layout drawnLayout;
    @Nullable
    private static volatile Layout litLayout;

    private static final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
        onPreDraw();
        return true;
    };

    private static final ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
        if (SystemClock.uptimeMillis() - lastPageTouch <= TOUCH_SCROLL_WINDOW_MS) onInteraction();
    };

    private static final Runnable restoreLight = () -> setDimmed(false);
    private static final Runnable fadeStep = AmbientLightController::stepFade;

    /**
     * Injection point.
     */
    public static void initialize(ViewGroup controlsOverlay) {
        if (!ENABLED) return;

        try {
            overlayRef = new WeakReference<>(controlsOverlay);
            if (!observerRegistered) {
                observerRegistered = true;
                Utils.getContext().registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onStateChanged();
                    }
                }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                PlayerType.getOnChange().addObserver(type -> onStateChanged());
            }
            mainHandler.post(AmbientLightController::update);
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setVideoTime(long videoTime) {
        if (!ENABLED || videoTime == lastVideoTime) return;

        lastVideoTime = videoTime;
        lastVideoTimeChange = SystemClock.uptimeMillis();
        mainHandler.post(AmbientLightController::scheduleTick);
    }

    /**
     * Injection point. Called when the app resumes and whenever the toggle changes.
     */
    public static void setAmbientModeToggle(@Nullable Boolean enabled) {
        if (!ENABLED || enabled == null || enabled == ambientModeToggle) return;

        ambientModeToggle = enabled;
        mainHandler.post(AmbientLightController::onStateChanged);
    }

    /**
     * Injection point.
     */
    public static int getLithoColor(Drawable drawable, int color) {
        // The cards of the feed card layout stay opaque, as their content has its own opaque backgrounds.
        if (!seeThrough || FeedCardLayoutPatch.cardBackgrounds.containsKey(drawable)) return color;

        try {
            final int replacement = AmbientLightMath.seeThroughColor(color, pageColor);
            Rect bounds = drawable.getBounds();
            if (replacement == 0 && bounds.width() < buttonMaxWidth && bounds.height() < buttonMaxHeight) {
                return color;
            }
            return replacement;
        } catch (Exception ex) {
            Logger.printException(() -> "getLithoColor failure", ex);
            return color;
        }
    }

    /**
     * @return If YouTube's own ambient mode is replaced by this light.
     */
    public static boolean replacesAmbientMode() {
        return ENABLED;
    }

    private static void onStateChanged() {
        retries = 0;
        update();
    }

    private static void update() {
        try {
            PlayerType type = PlayerType.getCurrent();
            // Battery saver turns the light off like Ambient mode, unless that restriction is bypassed.
            final boolean on = ambientModeToggle && !AmbientModePatch.bypassAmbientModeRestrictions(
                    (PowerManager) Utils.getContext().getSystemService(Context.POWER_SERVICE));
            // The page content can be built before the light shows, and must be see-through already.
            final boolean watching = on && (type == PlayerType.WATCH_WHILE_MAXIMIZED
                    || type == PlayerType.WATCH_WHILE_FULLSCREEN
                    || type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN);
            if (watching && !seeThrough) {
                Resources resources = Resources.getSystem();
                buttonMaxWidth = (int) (BUTTON_MAX_WIDTH_SCREEN_RATIO * Math.min(
                        resources.getDisplayMetrics().widthPixels, resources.getDisplayMetrics().heightPixels));
                buttonMaxHeight = (int) (BUTTON_MAX_HEIGHT_DP * resources.getDisplayMetrics().density);
                pageColor = Utils.isDarkModeEnabled() ? DARK_PAGE_COLOR : LIGHT_PAGE_COLOR;
            }
            seeThrough = watching;

            if (!on) {
                stop();
            } else if (type == PlayerType.WATCH_WHILE_MAXIMIZED || type == PlayerType.WATCH_WHILE_FULLSCREEN) {
                if (!active && !start() && retries++ < MAX_RETRIES) scheduleUpdate();
            } else if (type != PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN) {
                stop();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "update failure", ex);
        }
    }

    private static boolean start() {
        ViewGroup overlay = overlayRef.get();
        if (overlay == null || !overlay.isAttachedToWindow() || !findPlayer(overlay)) return false;

        View video = videoRef.get();
        Activity activity = Utils.getActivity();
        if (activity != null && activity.isInPictureInPictureMode()) return false;

        View root = video.getRootView();
        View page = findWatchPage(root, video);
        if (page == null || !(root instanceof FrameLayout)) return false;

        if (readback == null) {
            readback = Bitmap.createBitmap(READBACK_WIDTH, READBACK_HEIGHT, Bitmap.Config.ARGB_8888, false);
        }
        pageRef = new WeakReference<>(page);
        rootRef = new WeakReference<>(root);
        active = true;
        settled = false;
        pageCleared = false;
        root.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        root.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        View touchWatcher = new TouchWatcher(root.getContext());
        ((ViewGroup) root).addView(touchWatcher);
        touchWatcherRef = new WeakReference<>(touchWatcher);
        updateLayout();
        scheduleTick();
        return true;
    }

    private static void stop() {
        if (!active) return;
        active = false;
        layout = null;

        View root = rootRef.get();
        if (root != null) {
            root.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
            root.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            ((ViewGroup) root).removeView(touchWatcherRef.get());
        }
        removeSurfaces(pageSurfaces, 0);
        removeSurfaces(playerSurfaces, 0);
        mainHandler.removeCallbacks(restoreLight);
        mainHandler.removeCallbacks(fadeStep);
        dimmed = false;
        strength = FULL_STRENGTH;
        SeeThroughPage.restore();
        // The next video must not start from the black bars and colors of this one.
        if (worker != null) worker.post(AmbientLightController::resetPicture);
    }

    /**
     * Worker thread.
     */
    private static void resetPicture() {
        drawnLayout = null;
        resetSmoothing = true;
    }

    /**
     * Only runs when the app redraws, so an idle page costs nothing.
     */
    private static void onPreDraw() {
        try {
            if (!active) return;
            updateLayout();
            if (!pageCleared) return;

            SeeThroughPage.keep();
            final long now = SystemClock.uptimeMillis();
            if (now - lastPageScan >= PAGE_SCAN_INTERVAL_MS) {
                lastPageScan = now;
                clearPage();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPreDraw failure", ex);
        }
    }

    private static void clearPage() {
        View page = pageRef.get();
        View video = videoRef.get();
        ViewGroup player = playerRef.get();
        if (page instanceof ViewGroup && video != null && player != null) {
            View branch = childContaining((ViewGroup) page, video);
            SeeThroughPage.apply(page, branch == null ? player : branch, pageColor);
        }
    }

    /**
     * Places the light around the video surface: on the page around the player, and on the part
     * of the player the surface does not cover. Nothing is ever painted over the video itself,
     * so black bars that are part of the video stay as they are.
     */
    private static void updateLayout() {
        if (!active) return;
        View root = rootRef.get();
        View video = videoRef.get();
        ViewGroup player = playerRef.get();
        if (root == null || video == null || player == null || !video.isAttachedToWindow()) {
            stop();
            scheduleUpdate();
            return;
        }

        int[] location = new int[2];
        player.getLocationInWindow(location);
        final int left = location[0];
        final int top = location[1];
        final int right = left + player.getWidth();
        final int bottom = top + player.getHeight();
        int[] videoBounds = videoInWindow(video);

        // This runs for every frame the app draws, and the answer rarely changes.
        int[] geometry = {left, top, right, bottom, videoBounds[0], videoBounds[1], videoBounds[2], videoBounds[3],
                root.getWidth(), root.getHeight()};
        if (layout != null && Arrays.equals(geometry, lastGeometry)) return;
        lastGeometry = geometry;

        List<int[]> pageRects = new ArrayList<>(4);
        addRect(pageRects, 0, 0, root.getWidth(), top);
        addRect(pageRects, 0, top, left, bottom);
        addRect(pageRects, right, top, root.getWidth(), bottom);
        addRect(pageRects, 0, bottom, root.getWidth(), root.getHeight());

        List<int[]> playerRects = new ArrayList<>(4);
        if (player instanceof FrameLayout) {
            addRect(playerRects, left, top, right, videoBounds[1]);
            addRect(playerRects, left, videoBounds[1], videoBounds[0], videoBounds[3]);
            addRect(playerRects, videoBounds[2], videoBounds[1], right, videoBounds[3]);
            addRect(playerRects, left, videoBounds[3], right, bottom);
        }

        final int gridHeight = Math.max(2, LIGHT_COLUMNS * root.getHeight() / root.getWidth());
        List<Region> regions = new ArrayList<>(pageRects.size() + playerRects.size());
        placeSurfaces(pageSurfaces, (ViewGroup) root, 0, false, pageRects, regions);
        View branch = childContaining(player, video);
        placeSurfaces(playerSurfaces, player, player.indexOfChild(branch) + 1, true, playerRects, regions);
        layout = new Layout(videoBounds, root.getWidth(), root.getHeight(), gridHeight, regions);
        settled = false;
        scheduleTick();
    }

    private static int[] videoInWindow(View video) {
        int[] location = new int[2];
        video.getLocationInWindow(location);
        return new int[]{location[0], location[1],
                location[0] + video.getWidth(), location[1] + video.getHeight()};
    }

    private static void addRect(List<int[]> rects, int left, int top, int right, int bottom) {
        if (right - left >= MIN_RECT_SIZE && bottom - top >= MIN_RECT_SIZE) {
            rects.add(new int[]{left, top, right, bottom});
        }
    }

    private static void placeSurfaces(List<LightSurface> surfaces, ViewGroup parent, int insertIndex,
                                      boolean aboveVideo, List<int[]> rects, List<Region> regions) {
        int[] parentLocation = new int[2];
        parent.getLocationInWindow(parentLocation);
        for (int i = 0; i < rects.size(); i++) {
            int[] rect = rects.get(i);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    rect[2] - rect[0], rect[3] - rect[1], Gravity.TOP | Gravity.START);
            params.leftMargin = rect[0] - parentLocation[0];
            params.topMargin = rect[1] - parentLocation[1];

            final LightSurface surface;
            if (i < surfaces.size()) {
                surface = surfaces.get(i);
                surface.setLayoutParams(params);
            } else {
                surface = new LightSurface(parent.getContext(), AmbientLightController::requestFrame);
                surface.setZOrderMediaOverlay(aboveVideo);
                surfaces.add(surface);
                parent.addView(surface, insertIndex + i, params);
            }
            regions.add(new Region(surface, rect[0], rect[1], !aboveVideo));
        }
        removeSurfaces(surfaces, rects.size());
    }

    private static void removeSurfaces(List<LightSurface> surfaces, int keep) {
        while (surfaces.size() > keep) {
            LightSurface surface = surfaces.remove(surfaces.size() - 1);
            if (surface.getParent() instanceof ViewGroup) ((ViewGroup) surface.getParent()).removeView(surface);
        }
    }

    private static void requestFrame() {
        settled = false;
        scheduleTick();
    }

    private static void scheduleUpdate() {
        mainHandler.postDelayed(AmbientLightController::update, RETRY_DELAY_MS);
    }

    private static void scheduleTick() {
        if (active && !tickScheduled) {
            tickScheduled = true;
            mainHandler.post(AmbientLightController::tick);
        }
    }

    private static void tick() {
        tickScheduled = false;
        if (!active) return;

        try {
            View video = videoRef.get();
            Layout current = layout;
            // Changes of the layout schedule a tick again.
            if (video == null || video.getWindowVisibility() != View.VISIBLE
                    || current == null || current.regions.isEmpty()) return;

            final boolean playing = SystemClock.uptimeMillis() - lastVideoTimeChange < PLAYING_TIMEOUT_MS;
            if (!playing && settled) return;

            final int failures = captureFailures;
            final boolean failing = failures >= CAPTURE_FAILURES_BEFORE_BACKOFF;
            if (failing && !playing) return;
            if (failing && failures % CAPTURE_FAILURES_BEFORE_BACKOFF == 0) findVideoAgain();

            final long interval = failing ? FAILED_CAPTURE_INTERVAL_MS
                    : stillCaptures >= STILL_CAPTURES_BEFORE_SLOWDOWN ? STILL_CAPTURE_INTERVAL_MS : CAPTURE_INTERVAL_MS;
            if (!captureInFlight) capture(video, current);

            tickScheduled = true;
            mainHandler.postDelayed(AmbientLightController::tick, interval);
        } catch (Exception ex) {
            Logger.printException(() -> "tick failure", ex);
        }
    }

    private static Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("AmbientLight", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    private static void capture(View video, Layout current) {
        if (video.getWidth() <= 0 || video.getHeight() <= 0) {
            captureFailures++;
            return;
        }

        Bitmap bitmap = readback;
        captureInFlight = true;
        if (video instanceof SurfaceView) {
            // Before Android 14 the request blocks until the copy is finished.
            worker().post(() -> copySurface((SurfaceView) video, bitmap, current));
        } else {
            ((TextureView) video).getBitmap(bitmap);
            worker().post(() -> {
                process(bitmap, current);
                captureInFlight = false;
            });
        }
    }

    /**
     * Worker thread.
     */
    private static void copySurface(SurfaceView video, Bitmap bitmap, Layout current) {
        try {
            if (!video.getHolder().getSurface().isValid()) {
                onCaptureFailed();
                return;
            }
            PixelCopy.request(video, bitmap, result -> {
                if (result == PixelCopy.SUCCESS) {
                    captureFailures = 0;
                    process(bitmap, current);
                } else {
                    captureFailures++;
                }
                captureInFlight = false;
            }, worker());
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Video surface went away during the capture");
            onCaptureFailed();
        }
    }

    private static void onCaptureFailed() {
        captureFailures++;
        captureInFlight = false;
    }

    /**
     * YouTube can replace the video surface while the old one stays attached.
     */
    private static void findVideoAgain() {
        ViewGroup overlay = overlayRef.get();
        if (overlay != null && overlay.isAttachedToWindow() && findPlayer(overlay)) updateLayout();
    }

    /**
     * Worker thread.
     */
    private static void process(Bitmap bitmap, Layout current) {
        try {
            bitmap.getPixels(readbackPixels, 0, READBACK_WIDTH, 0, 0, READBACK_WIDTH, READBACK_HEIGHT);
            updateBars();
            AmbientLightMath.downsample(readbackPixels, READBACK_WIDTH, readbackBounds,
                    pictureColors, PICTURE_WIDTH, PICTURE_HEIGHT);

            final int difference = AmbientLightMath.difference(near, pictureColors);
            final boolean unchanged = difference <= UNCHANGED_DIFFERENCE;
            if (unchanged && current == drawnLayout) {
                stillCaptures++;
                if (stepCover()) {
                    present(current);
                } else {
                    settled = true;
                }
                return;
            }
            settled = false;
            stillCaptures = 0;
            AmbientLightMath.blend(near, pictureColors,
                    resetSmoothing || difference >= SCENE_CUT_DIFFERENCE ? 0 : SMOOTHING_KEEP);
            resetSmoothing = false;
            AmbientLightMath.downsample(near, PICTURE_WIDTH, FULL_PICTURE, far, FAR_WIDTH, FAR_HEIGHT);

            AmbientLightMath.toLinear(near, nearLinear);
            AmbientLightMath.toLinear(far, farLinear);
            final int width = LIGHT_COLUMNS;
            final int height = current.gridHeight;
            final int length = width * height * LIGHT_CHANNELS;
            if (light.length != length) {
                light = new float[length];
                lightSoft = new float[length];
                lightScratch = new float[length];
            }
            AmbientLightMath.render(nearLinear, PICTURE_WIDTH, PICTURE_HEIGHT, farLinear, FAR_WIDTH, FAR_HEIGHT,
                    current.video, current.windowWidth, current.windowHeight, light, width, height);
            AmbientLightMath.toLinear(new int[]{pageColor}, pageLinear);
            final float toGridX = width / (float) current.windowWidth;
            final float toGridY = height / (float) current.windowHeight;
            float[] gridVideo = {current.video[0] * toGridX, current.video[1] * toGridY,
                    current.video[2] * toGridX, current.video[3] * toGridY};
            AmbientLightMath.soften(light, lightSoft, lightScratch, width, height, gridVideo, pageLinear);
            wantedCover = Settings.ENHANCED_AMBIENT_MODE_READABLE_TEXT.get()
                    ? AmbientLightMath.readabilityCover(light, width, height,
                    current.video[3] * height / current.windowHeight, AmbientLightMath.luma(pageColor) < 128)
                    : 0f;
            final boolean coverMoved = stepCover();

            if (encodedLight.length != length) {
                encodedLight = new float[length];
                presentedLight = new float[length];
            }
            final float change = AmbientLightMath.encodeVisible(light, encodedLight, presentedLight, width, height, gridVideo);
            final boolean visible = current != drawnLayout || change >= MIN_VISIBLE_CHANGE;
            if (!visible && !coverMoved) return;

            if (visible) {
                float[] shown = encodedLight;
                encodedLight = presentedLight;
                presentedLight = shown;
                updateLightBitmap(width, height, gridVideo);
            }
            litLayout = current;
            if (present(current)) {
                drawnLayout = current;
                if (!pageCleared) mainHandler.post(AmbientLightController::onFrameShown);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "process failure", ex);
        }
    }

    /**
     * Worker thread. The bitmap has more than 8 bits per channel, as even a blur over dozens
     * of pixels leaves visible color blotches once the light is rounded to 8 bits.
     */
    private static void updateLightBitmap(int width, int height, float[] gridVideo) {
        Bitmap bitmap = lightBitmap;
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16);
            lightBitmap = bitmap;
            halfFloats = new short[width * height * BITMAP_CHANNELS];
            AmbientLightMath.fillAlpha(halfFloats);
        }
        AmbientLightMath.toHalfFloats(presentedLight, halfFloats, width, height, gridVideo);
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(halfFloats));
        // A shader keeps the pixels the bitmap had when the shader was created.
        lightPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    /**
     * Worker thread. Shows the current light at the current strength.
     *
     * @return False if some surface has no buffer yet.
     */
    private static boolean present(Layout current) {
        lightPaint.setAlpha(Math.min(255, strength * 255 / FULL_STRENGTH));
        final float scaleX = current.windowWidth / (float) LIGHT_COLUMNS;
        final float scaleY = current.windowHeight / (float) current.gridHeight;
        final int coverAlpha = Math.round(cover * strength * 255 / FULL_STRENGTH);
        final int coverColor = coverAlpha << 24 | (pageColor & 0xFFFFFF);
        boolean drawn = true;
        for (Region region : current.regions) {
            drawn &= region.surface.draw(lightPaint, region.left, region.top, scaleX, scaleY,
                    LIGHT_COLUMNS, current.gridHeight, pageColor, region.onPage ? coverColor : 0);
        }
        return drawn;
    }

    /**
     * Worker thread.
     *
     * @return If the cover changed.
     */
    private static boolean stepCover() {
        final float gap = wantedCover - cover;
        if (Math.abs(gap) < COVER_SETTLED) return false;
        cover += gap * COVER_EASING;
        return true;
    }

    private static void onInteraction() {
        final long now = SystemClock.uptimeMillis();
        if (now - lastInteraction < INTERACTION_THROTTLE_MS) return;
        lastInteraction = now;
        setDimmed(true);
        mainHandler.removeCallbacks(restoreLight);
        mainHandler.postDelayed(restoreLight, RESTORE_DELAY_MS);
    }

    private static void setDimmed(boolean value) {
        if (dimmed == value) return;
        dimmed = value;
        lastFadeStep = SystemClock.uptimeMillis();
        mainHandler.removeCallbacks(fadeStep);
        mainHandler.post(fadeStep);
    }

    /**
     * The only time the light is drawn without a new capture, so the fade is smooth.
     */
    private static void stepFade() {
        final long now = SystemClock.uptimeMillis();
        final long elapsed = Math.max(1, now - lastFadeStep);
        lastFadeStep = now;
        final int range = FULL_STRENGTH - DIMMED_STRENGTH;
        final int current = strength;
        final int next = dimmed
                ? Math.max(DIMMED_STRENGTH, current - (int) (range * elapsed / DIM_DURATION_MS))
                : Math.min(FULL_STRENGTH, current + Math.max(1, (int) (range * elapsed / RESTORE_DURATION_MS)));
        strength = next;
        worker().post(AmbientLightController::presentFade);
        if (next != (dimmed ? DIMMED_STRENGTH : FULL_STRENGTH)) mainHandler.postDelayed(fadeStep, FADE_STEP_MS);
    }

    private static void presentFade() {
        try {
            Layout current = litLayout;
            if (current != null && current == layout) present(current);
        } catch (Exception ex) {
            Logger.printException(() -> "presentFade failure", ex);
        }
    }

    /**
     * Sees touches without taking them, which the scroll callbacks cannot tell apart.
     */
    private static final class TouchWatcher extends View {
        TouchWatcher(Context context) {
            super(context);
            setWillNotDraw(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isOnPlayer(event)) {
                lastPageTouch = SystemClock.uptimeMillis();
                onInteraction();
            }
            return false;
        }

        private static boolean isOnPlayer(MotionEvent event) {
            ViewGroup player = playerRef.get();
            if (player == null) return false;
            int[] location = new int[2];
            player.getLocationInWindow(location);
            return event.getRawY() >= location[1] && event.getRawY() <= location[1] + player.getHeight();
        }
    }

    /**
     * Worker thread. Black bars in the video would darken the light, so colors are only taken
     * from what most captures agree is the picture. A wrong guess only changes the colors a
     * little. A dark scene only makes the picture look smaller, so it does not count against it.
     */
    private static void updateBars() {
        int[] seen = AmbientLightMath.pictureBounds(readbackPixels, READBACK_WIDTH, READBACK_HEIGHT);
        if (seen == null) return;

        if (drawnLayout == null) {
            barsCandidate = null;
            barsVotes = 0;
            barsCaptures = 0;
            setPictureBounds(seen);
            return;
        }

        int[] candidate = barsCandidate;
        if (candidate != null && isCloseTo(seen, candidate)) {
            barsVotes++;
        } else if (candidate == null || !isInside(seen, candidate)) {
            if (--barsVotes < 0) {
                barsCandidate = seen;
                barsVotes = 1;
            }
        }
        if (++barsCaptures < BARS_STABLE_CAPTURES) return;

        int[] agreed = barsVotes >= BARS_MIN_VOTES ? barsCandidate : null;
        barsCandidate = null;
        barsVotes = 0;
        barsCaptures = 0;
        if (agreed != null) setPictureBounds(agreed);
    }

    private static void setPictureBounds(int[] seen) {
        int[] bounds = AmbientLightMath.withoutOneSidedBars(seen, READBACK_WIDTH, READBACK_HEIGHT);
        if (isCloseTo(bounds, readbackBounds)) return;

        readbackBounds = bounds;
        resetSmoothing = true;
    }

    private static boolean isInside(int[] inner, int[] outer) {
        return inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3];
    }

    private static boolean isCloseTo(int[] first, int[] second) {
        for (int i = 0; i < first.length; i++) {
            if (Math.abs(first[i] - second[i]) > BARS_TOLERANCE) return false;
        }
        return true;
    }

    private static void onFrameShown() {
        if (active && !pageCleared) {
            pageCleared = true;
            clearPage();
        }
    }

    /**
     * Finds the video surface that is underneath the player controls,
     * as related videos can have autoplaying previews with surfaces of their own.
     */
    private static boolean findPlayer(ViewGroup overlay) {
        int[] location = new int[2];
        overlay.getLocationInWindow(location);
        final float x = location[0] + overlay.getWidth() / 2f;
        final float y = location[1] + overlay.getHeight() / 2f;

        ViewParent parent = overlay.getParent();
        for (int i = 0; i < MAX_PLAYER_ANCESTORS && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            View video = findSurface((View) parent, x, y, null);
            if (video != null) {
                videoRef = new WeakReference<>(video);
                playerRef = new WeakReference<>((ViewGroup) parent);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static View findSurface(View view, float x, float y, @Nullable View best) {
        if (view.getVisibility() != View.VISIBLE) return best;

        if ((view instanceof SurfaceView && !(view instanceof LightSurface)) || view instanceof TextureView) {
            int[] location = new int[2];
            view.getLocationInWindow(location);
            final boolean contains = x >= location[0] && x <= location[0] + view.getWidth()
                    && y >= location[1] && y <= location[1] + view.getHeight();
            final long area = (long) view.getWidth() * view.getHeight();
            if (contains && area > 0 && (best == null || area > (long) best.getWidth() * best.getHeight())) {
                best = view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = findSurface(group.getChildAt(i), x, y, best);
            }
        }
        return best;
    }

    @Nullable
    private static View findWatchPage(View root, View video) {
        for (int id : WATCH_PAGE_IDS) {
            View found = id == 0 ? null : root.findViewById(id);
            if (found instanceof ViewGroup && childContaining((ViewGroup) found, video) != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static View childContaining(ViewGroup ancestor, View descendant) {
        View current = descendant;
        while (current != null) {
            ViewParent parent = current.getParent();
            if (parent == ancestor) return current;
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
}
