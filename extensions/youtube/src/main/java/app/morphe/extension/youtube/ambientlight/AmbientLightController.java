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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Matrix;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
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
    /** Scrolling has stopped once no scroll was seen for this long, as the scrolls are throttled. */
    private static final long SCROLL_END_MS = 200;
    private static final long FADE_STEP_MS = 33;
    /** The surfaces show the light again once nothing has moved for this long. */
    private static final long REST_MS = 300;
    /** Time for the buffers of the surfaces to get on screen after they were drawn. */
    private static final long SURFACES_SETTLE_MS = 50;
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
    /** Captures in a row that can be converted ones. A change that stays longer is a change of the video. */
    private static final int MAX_CONVERTED_CAPTURES = 15;

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

    private record Region(LightSurface surface, int left, int top, boolean onPage) {
    }

    /**
     * Immutable snapshot of where the video and the light are. Layouts are compared by
     * identity, never with equals.
     */
    private record Layout(int[] video, int windowWidth, int windowHeight, int gridHeight,
                          List<Region> regions, boolean hasLight) {
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
    private static final AtomicInteger captureFailures = new AtomicInteger();
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

    /**
     * While the player moves or the page fades, the light is drawn in the app window, over the
     * surfaces: the window is drawn in the frame of everything else, and a surface is not.
     * Written on the main thread.
     */
    private static volatile boolean veiled;
    /** If the surfaces are in view, where the layout wants them. Main thread. */
    private static boolean surfacesPlaced;
    /** Since when all surfaces show the light of the layout, or 0. Main thread. */
    private static long surfacesShownSince;
    private static long lastMove;
    private static WeakReference<LightView> windowLightRef = new WeakReference<>(null);
    private static WeakReference<LightView> playerLightRef = new WeakReference<>(null);
    private static final int[] drawLocation = new int[2];
    /** What the window draws: the light as the page shows it, and as the player shows it. Main thread. */
    private static final Paint[] windowPaints = {
            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG)};
    private static final int[] windowFilterMul = {0xFFFFFF, 0xFFFFFF};
    private static final int[] windowFilterAdd = new int[2];
    private static final Paint windowColorPaint = new Paint();
    private static final Matrix windowShaderMatrix = new Matrix();
    @Nullable
    private static BitmapShader windowShader;
    private static int windowGridHeight;
    /** Up to two rectangles that a view does not fill, and the edges of the bands between them. */
    private static final int[] windowHoles = new int[8];
    private static final int[] windowBands = new int[6];
    private static final Rect windowHoleBounds = new Rect();
    /** The newest light, for the window. Written by the worker thread. */
    @Nullable
    private static volatile Bitmap newestLightBitmap;
    /** Counts the new buffers of the surfaces, which are empty. Written on the main thread. */
    private static volatile int surfaceBuffers;
    /** The layout whose surfaces all got the light, when {@link #surfaceBuffers} was {@link #shownBuffers}. */
    @Nullable
    private static volatile Layout shownLayout;
    private static volatile int shownBuffers;

    /** The Ambient mode toggle of the player flyout menu, which turns the light off too. */
    private static volatile boolean ambientModeToggle = true;
    private static volatile boolean seeThrough;
    private static volatile int buttonMaxWidth;
    private static volatile int buttonMaxHeight;
    private static volatile long lastVideoTime = -1;
    private static volatile long lastVideoTimeChange;
    /** False while the light still has to reach the current picture. */
    private static volatile boolean settled;
    private static final AtomicInteger stillCaptures = new AtomicInteger();
    /** Set when a surface got a new buffer, which is empty until the light is drawn again. */
    private static volatile boolean redraw;
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
    /** The last capture as it came, and as the light used it. */
    private static final int[] lastCapture = new int[PICTURE_WIDTH * PICTURE_HEIGHT];
    private static final int[] lastPicture = new int[PICTURE_WIDTH * PICTURE_HEIGHT];
    private static boolean hasLastPicture;
    private static int convertedCaptures;
    private static int brighterCaptures;
    private static final int[] near = new int[PICTURE_WIDTH * PICTURE_HEIGHT];
    private static final int[] far = new int[FAR_WIDTH * FAR_HEIGHT];
    private static final float[] nearLinear = new float[PICTURE_WIDTH * PICTURE_HEIGHT * LIGHT_CHANNELS];
    private static final float[] farLinear = new float[FAR_WIDTH * FAR_HEIGHT * LIGHT_CHANNELS];
    private static final float[] pageLinear = new float[LIGHT_CHANNELS];
    private static float[] light = new float[0];
    private static float wantedCover;
    /** Written by the worker thread. */
    private static volatile float cover;
    private static float[] encodedLight = new float[0];
    private static float[] presentedLight = new float[0];
    private static float[] lightSoft = new float[0];
    private static float[] lightScratch = new float[0];
    private static short[] halfFloats = new short[0];
    private static final Paint lightPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    /** The worker writes one while the other is drawn. */
    private static final Bitmap[] lightBitmaps = new Bitmap[2];
    private static int nextLightBitmap;
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
        if (SystemClock.uptimeMillis() - lastPageTouch <= TOUCH_SCROLL_WINDOW_MS) onInteraction(true);
    };

    /**
     * The overlay is attached a moment after the player comes back from the miniplayer, and
     * the player cannot be found before that.
     */
    private static final View.OnAttachStateChangeListener overlayAttachListener = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View overlay) {
            mainHandler.post(AmbientLightController::onStateChanged);
        }

        @Override
        public void onViewDetachedFromWindow(View overlay) {
        }
    };

    private static final Runnable restoreLight = () -> setDimmed(false);
    private static final Runnable fadeStep = AmbientLightController::stepFade;
    private static final Runnable tickRunnable = AmbientLightController::tick;
    private static final Runnable unveilRunnable = AmbientLightController::unveilWhenRested;
    private static final Runnable windowLightRunnable = AmbientLightController::showNewestLight;

    /**
     * Drawables that keep their color until they are mounted in the window of the watch page.
     * Main thread.
     */
    private static final Map<Drawable, Boolean> pendingDrawables = new WeakHashMap<>();
    /** Drawables the light shows through. Main thread. */
    private static final Map<Drawable, Boolean> seeThroughDrawables = new WeakHashMap<>();

    /**
     * Injection point.
     */
    public static void initialize(ViewGroup controlsOverlay) {
        if (!ENABLED) return;

        try {
            overlayRef = new WeakReference<>(controlsOverlay);
            controlsOverlay.removeOnAttachStateChangeListener(overlayAttachListener);
            controlsOverlay.addOnAttachStateChangeListener(overlayAttachListener);
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
            if (replacement == color
                    || (replacement == 0 && bounds.width() < buttonMaxWidth && bounds.height() < buttonMaxHeight)) {
                return color;
            }
            // A color is often chosen before its drawable is on screen, and only the window of the
            // watch page has the light behind it. Another window, such as the comment box that
            // opens with the keyboard, would show its own background through the drawable.
            View host = hostOf(drawable);
            if (host != null && host.getRootView() == rootRef.get()) {
                pendingDrawables.remove(drawable);
                seeThroughDrawables.put(drawable, Boolean.TRUE);
                return replacement;
            }

            seeThroughDrawables.remove(drawable);
            pendingDrawables.put(drawable, Boolean.TRUE);
            return color;
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
            // On its way to the miniplayer, the page fades out, and the light with it. On its way
            // back they fade in.
            final boolean miniplayerSlide = type == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED
                    && (active || playerIsShown());
            final boolean sliding = type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN || miniplayerSlide;
            final boolean watching = on && (type == PlayerType.WATCH_WHILE_MAXIMIZED
                    || type == PlayerType.WATCH_WHILE_FULLSCREEN || sliding);
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
            } else if (!sliding) {
                stop();
            } else if (miniplayerSlide && !active) {
                start();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "update failure", ex);
        }
    }

    private static boolean start() {
        ViewGroup overlay = overlayRef.get();
        // The controls are detached while the player is in the miniplayer and on its way back.
        if (overlay == null || !(overlay.isAttachedToWindow() ? findPlayer(overlay) : playerIsShown())) return false;

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
        // Failed captures of an earlier video must not slow the first ones of this video down.
        captureFailures.set(0);
        stillCaptures.set(0);
        ViewTreeObserver viewTreeObserver = root.getViewTreeObserver();
        viewTreeObserver.addOnPreDrawListener(preDrawListener);
        viewTreeObserver.addOnScrollChangedListener(scrollListener);
        View touchWatcher = new TouchWatcher(root.getContext());
        ((ViewGroup) root).addView(touchWatcher);
        touchWatcherRef = new WeakReference<>(touchWatcher);
        // Under everything else in the window, as the surfaces are.
        LightView windowLight = new LightView(root.getContext(), false);
        ((ViewGroup) root).addView(windowLight, 0);
        windowLightRef = new WeakReference<>(windowLight);
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
            ViewTreeObserver viewTreeObserver = root.getViewTreeObserver();
            viewTreeObserver.removeOnPreDrawListener(preDrawListener);
            viewTreeObserver.removeOnScrollChangedListener(scrollListener);
            ((ViewGroup) root).removeView(touchWatcherRef.get());
            ((ViewGroup) root).removeView(windowLightRef.get());
        }
        removeSurfaces(pageSurfaces, 0);
        removeSurfaces(playerSurfaces, 0);
        LightView playerLight = playerLightRef.get();
        if (playerLight != null && playerLight.getParent() instanceof ViewGroup parent) parent.removeView(playerLight);
        playerLightRef = new WeakReference<>(null);
        veiled = false;
        surfacesPlaced = false;
        shownLayout = null;
        newestLightBitmap = null;
        windowGridHeight = 0;
        windowShader = null;
        for (Paint paint : windowPaints) paint.setShader(null);
        mainHandler.removeCallbacks(unveilRunnable);
        mainHandler.removeCallbacks(windowLightRunnable);
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
        hasLastPicture = false;
    }

    /**
     * Only runs when the app redraws, so an idle page costs nothing.
     */
    private static void onPreDraw() {
        try {
            if (!active) return;
            updateDrawables();
            updateLayout();
            if (!active || !pageCleared) return;

            final boolean pageChanged = SeeThroughPage.keep();
            final long now = SystemClock.uptimeMillis();
            if (pageChanged || now - lastPageScan >= PAGE_SCAN_INTERVAL_MS) {
                lastPageScan = now;
                clearPage();
            }
            // The page fades too, without the player moving.
            if (isSliding() || SeeThroughPage.isPageContentFading()) onMoved();
            if (veiled) SeeThroughPage.invalidateMovedLight();
        } catch (Exception ex) {
            Logger.printException(() -> "onPreDraw failure", ex);
        }
    }

    @Nullable
    private static View hostOf(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable drawableCallback) {
            callback = drawableCallback.getCallback();
        }
        return callback instanceof View view ? view : null;
    }

    /**
     * Litho mounts a drawable again, also in another window of the app such as the comment box,
     * and keeps its color when its bounds stay the same. This runs before the watch page draws,
     * so a drawable shows the light only while it is in the window of the watch page.
     */
    private static void updateDrawables() {
        if (seeThroughDrawables.isEmpty() && pendingDrawables.isEmpty()) return;
        View root = rootRef.get();
        List<Drawable> changed = new ArrayList<>();

        // Drawables that left the watch page get their own color back, and wait to be mounted again.
        for (Iterator<Drawable> iterator = seeThroughDrawables.keySet().iterator(); iterator.hasNext(); ) {
            Drawable drawable = iterator.next();
            View host = hostOf(drawable);
            if (host == null || host.getRootView() != root) {
                iterator.remove();
                changed.add(drawable);
            }
        }
        // Drawables that are mounted in another window keep their own color.
        for (Iterator<Drawable> iterator = pendingDrawables.keySet().iterator(); iterator.hasNext(); ) {
            Drawable drawable = iterator.next();
            View host = hostOf(drawable);
            if (host == null) continue;
            iterator.remove();
            if (host.getRootView() == root) changed.add(drawable);
        }

        // This calls getLithoColor, which adds the drawables to the maps again.
        for (Drawable drawable : changed) {
            chooseColorAgain(drawable);
        }
    }

    /**
     * The color of a drawable is chosen when its bounds change.
     */
    private static void chooseColorAgain(Drawable drawable) {
        Rect bounds = new Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 0, 0);
        drawable.setBounds(bounds);
        drawable.invalidateSelf();
    }

    private static void clearPage() {
        View page = pageRef.get();
        View video = videoRef.get();
        ViewGroup player = playerRef.get();
        if (page instanceof ViewGroup pageViewGroup && video != null && player != null) {
            View branch = childContaining(pageViewGroup, video);
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
        // While the page scrolls, the player gets shorter and crops the video, which keeps its size.
        int[] videoBounds = videoInWindow(video);
        videoBounds[0] = Math.max(videoBounds[0], left);
        videoBounds[1] = Math.max(videoBounds[1], top);
        videoBounds[2] = Math.min(videoBounds[2], right);
        videoBounds[3] = Math.min(videoBounds[3], bottom);

        LightView playerLight = playerLightRef.get();
        if (player instanceof FrameLayout && (playerLight == null || playerLight.getParent() != player)) {
            if (playerLight != null && playerLight.getParent() instanceof ViewGroup parent) parent.removeView(playerLight);
            playerLight = new LightView(player.getContext(), true);
            // Above the video and the surfaces, which clear what is under them in the window,
            // and below the controls of the player.
            int index = player.indexOfChild(childContaining(player, video)) + 1;
            for (LightSurface surface : playerSurfaces) {
                index = Math.max(index, player.indexOfChild(surface) + 1);
            }
            player.addView(playerLight, index);
            playerLightRef = new WeakReference<>(playerLight);
        }

        // This runs for every frame the app draws, and the answer rarely changes.
        int[] geometry = {left, top, right, bottom, videoBounds[0], videoBounds[1], videoBounds[2], videoBounds[3],
                root.getWidth(), root.getHeight()};
        if (layout != null && Arrays.equals(geometry, lastGeometry)) return;
        lastGeometry = geometry;

        // A video that fills the window leaves no room for light.
        final boolean hasLight = videoBounds[0] >= MIN_RECT_SIZE || videoBounds[1] >= MIN_RECT_SIZE
                || root.getWidth() - videoBounds[2] >= MIN_RECT_SIZE || root.getHeight() - videoBounds[3] >= MIN_RECT_SIZE;
        // Without surfaces: the window draws the light until the player rests.
        layout = new Layout(videoBounds, root.getWidth(), root.getHeight(), gridHeight(root),
                Collections.emptyList(), hasLight);
        settled = false;
        onMoved();
        // The view draws the light around the video, and the video has moved in the view.
        if (playerLight != null) playerLight.invalidate();
        SeeThroughPage.invalidateBackdropLight();
        scheduleTick();
    }

    private static int gridHeight(View root) {
        return Math.max(2, LIGHT_COLUMNS * root.getHeight() / Math.max(1, root.getWidth()));
    }

    private static boolean isSliding() {
        PlayerType type = PlayerType.getCurrent();
        return type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
                || type == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED;
    }

    /**
     * Something moves in this frame. From this frame on the window draws the light, until
     * all has rested and the surfaces show the light again.
     */
    private static void onMoved() {
        lastMove = SystemClock.uptimeMillis();
        surfacesShownSince = 0;
        if (surfacesPlaced) {
            surfacesPlaced = false;
            // A surface follows its view in the frame the view is drawn in, so the surfaces are
            // gone in this frame. Hiding or removing a surface is not tied to the frame on every
            // version of Android.
            View root = rootRef.get();
            final float away = root == null ? 0f : 2f * root.getWidth();
            for (LightSurface surface : pageSurfaces) surface.setTranslationX(away);
            for (LightSurface surface : playerSurfaces) surface.setTranslationX(away);
            Layout current = layout;
            if (current != null && !current.regions().isEmpty()) {
                layout = new Layout(current.video(), current.windowWidth(), current.windowHeight(),
                        current.gridHeight(), Collections.emptyList(), current.hasLight());
                settled = false;
                scheduleTick();
            }
        }
        if (!veiled) {
            veiled = true;
            showNewestLight();
        }
        mainHandler.removeCallbacks(unveilRunnable);
        mainHandler.postDelayed(unveilRunnable, REST_MS);
    }

    /**
     * Gives the light back to the surfaces once nothing has moved for a while: puts them in
     * place under the light of the window, and stops drawing in the window when all of them
     * show the light. The worker thread runs this again whenever it has drawn the surfaces.
     */
    private static void unveilWhenRested() {
        try {
            mainHandler.removeCallbacks(unveilRunnable);
            if (!active || !veiled) return;
            final long now = SystemClock.uptimeMillis();
            // The surfaces cannot fade with the page, which a player that is held on its way leaves faded.
            if (isSliding()) lastMove = now;
            if (now - lastMove < REST_MS) {
                mainHandler.postDelayed(unveilRunnable, REST_MS - (now - lastMove));
                return;
            }
            if (!surfacesPlaced) placeSurfaces();
            Layout current = layout;
            if (!surfacesPlaced || current == null || (!current.regions().isEmpty()
                    && (shownLayout != current || shownBuffers != surfaceBuffers))) {
                surfacesShownSince = 0;
                return;
            }
            // A buffer is on screen a few frames after it was drawn.
            if (surfacesShownSince == 0) surfacesShownSince = now;
            final long shown = now - surfacesShownSince;
            if (shown < SURFACES_SETTLE_MS) {
                mainHandler.postDelayed(unveilRunnable, SURFACES_SETTLE_MS - shown);
                return;
            }
            veiled = false;
            invalidateWindowLight();
        } catch (Exception ex) {
            Logger.printException(() -> "unveilWhenRested failure", ex);
        }
    }

    /**
     * Places the surfaces for the layout of the window as it is now. Main thread.
     */
    private static void placeSurfaces() {
        View root = rootRef.get();
        View video = videoRef.get();
        ViewGroup player = playerRef.get();
        Layout current = layout;
        int[] geometry = lastGeometry;
        if (root == null || video == null || player == null || current == null || geometry == null) return;
        final int left = geometry[0];
        final int top = geometry[1];
        final int right = geometry[2];
        final int bottom = geometry[3];
        int[] videoBounds = current.video();

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

        List<Region> regions = new ArrayList<>(pageRects.size() + playerRects.size());
        placeSurfaces(pageSurfaces, (ViewGroup) root, 0, false, pageRects, regions);
        View branch = childContaining(player, video);
        placeSurfaces(playerSurfaces, player, player.indexOfChild(branch) + 1, true, playerRects, regions);
        surfacesPlaced = true;
        // A new layout, so that the light is drawn on the surfaces also when the picture is still.
        layout = new Layout(videoBounds, current.windowWidth(), current.windowHeight(), current.gridHeight(),
                regions, current.hasLight());
        settled = false;
        scheduleTick();
    }

    private static int[] videoInWindow(View video) {
        int[] location = new int[2];
        video.getLocationInWindow(location);
        // Zooming the video scales a parent of the surface, and the surface keeps its size.
        float scaleX = 1f;
        float scaleY = 1f;
        Object view = video;
        while (view instanceof View scaled) {
            scaleX *= scaled.getScaleX();
            scaleY *= scaled.getScaleY();
            view = scaled.getParent();
        }
        return new int[]{location[0], location[1],
                location[0] + Math.round(video.getWidth() * scaleX),
                location[1] + Math.round(video.getHeight() * scaleY)};
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

        for (int i = 0, size = rects.size(); i < size; i++) {
            int[] rect = rects.get(i);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    rect[2] - rect[0], rect[3] - rect[1], Gravity.TOP | Gravity.START);
            params.leftMargin = rect[0] - parentLocation[0];
            params.topMargin = rect[1] - parentLocation[1];

            final LightSurface surface;
            if (i < surfaces.size()) {
                surface = surfaces.get(i);
                surface.setLayoutParams(params);
                surface.setTranslationX(0f);
            } else {
                surface = new LightSurface(parent.getContext(), AmbientLightController::requestFrame);
                surface.setAboveVideo(aboveVideo);
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
        redraw = true;
        settled = false;
        surfaceBuffers++;
        // The next capture is half a second away while the picture is still.
        if (stillCaptures.get() >= STILL_CAPTURES_BEFORE_SLOWDOWN) {
            stillCaptures.set(0);
            mainHandler.removeCallbacks(tickRunnable);
            tickScheduled = false;
        }
        scheduleTick();
    }

    private static void scheduleUpdate() {
        mainHandler.postDelayed(AmbientLightController::update, RETRY_DELAY_MS);
    }

    private static void scheduleTick() {
        if (active && !tickScheduled) {
            tickScheduled = true;
            mainHandler.post(tickRunnable);
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
                    || current == null || !current.hasLight()) return;

            final boolean playing = SystemClock.uptimeMillis() - lastVideoTimeChange < PLAYING_TIMEOUT_MS;
            if (!playing && settled) return;

            final int failures = captureFailures.get();
            final boolean failing = failures >= CAPTURE_FAILURES_BEFORE_BACKOFF;
            if (failing && !playing) return;
            // The surface that is read is not shown, so the video can be on another surface by now.
            if (video.getAlpha() == 0 || (failing && failures % CAPTURE_FAILURES_BEFORE_BACKOFF == 0)) findVideoAgain();

            final long interval = failing ? FAILED_CAPTURE_INTERVAL_MS
                    : stillCaptures.get() >= STILL_CAPTURES_BEFORE_SLOWDOWN ? STILL_CAPTURE_INTERVAL_MS : CAPTURE_INTERVAL_MS;
            if (!captureInFlight) capture(video, current);

            tickScheduled = true;
            mainHandler.postDelayed(tickRunnable, interval);
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
            captureFailures.incrementAndGet();
            return;
        }

        Bitmap bitmap = readback;
        // Created when the light starts, before the first tick.
        if (bitmap == null) return;
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
                    captureFailures.set(0);
                    process(bitmap, current);
                } else {
                    captureFailures.incrementAndGet();
                }
                captureInFlight = false;
            }, worker());
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Video surface went away during the capture");
            onCaptureFailed();
        }
    }

    private static void onCaptureFailed() {
        captureFailures.incrementAndGet();
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

            // A device cannot convert a converted capture again, so two such steps in a row are
            // the video getting brighter.
            brighterCaptures = hasLastPicture && AmbientLightMath.isConverted(lastCapture, pictureColors)
                    ? brighterCaptures + 1 : 0;
            final boolean converted = hasLastPicture && brighterCaptures < 2
                    && convertedCaptures < MAX_CONVERTED_CAPTURES
                    && AmbientLightMath.isConverted(lastPicture, pictureColors);
            System.arraycopy(pictureColors, 0, lastCapture, 0, lastCapture.length);
            if (converted) {
                convertedCaptures++;
                AmbientLightMath.undoConversion(pictureColors);
            } else {
                convertedCaptures = 0;
            }
            hasLastPicture = true;
            System.arraycopy(pictureColors, 0, lastPicture, 0, lastPicture.length);

            final int difference = AmbientLightMath.difference(near, pictureColors);
            final boolean unchanged = difference <= UNCHANGED_DIFFERENCE;
            final boolean emptySurface = redraw;
            redraw = false;
            if (unchanged && current == drawnLayout) {
                stillCaptures.incrementAndGet();
                if (stepCover() || emptySurface) {
                    if (!present(current)) redraw = true;
                } else {
                    settled = true;
                }
                return;
            }
            settled = false;
            stillCaptures.set(0);
            AmbientLightMath.blend(near, pictureColors,
                    resetSmoothing || difference >= SCENE_CUT_DIFFERENCE ? 0 : SMOOTHING_KEEP);
            resetSmoothing = false;
            AmbientLightMath.downsample(near, PICTURE_WIDTH, FULL_PICTURE, far, FAR_WIDTH, FAR_HEIGHT);

            AmbientLightMath.toLinear(near, nearLinear);
            AmbientLightMath.toLinear(far, farLinear);
            final int width = LIGHT_COLUMNS;
            final int height = current.gridHeight();
            final int length = width * height * LIGHT_CHANNELS;
            if (light.length != length) {
                light = new float[length];
                lightSoft = new float[length];
                lightScratch = new float[length];
            }
            AmbientLightMath.render(nearLinear, PICTURE_WIDTH, PICTURE_HEIGHT, farLinear, FAR_WIDTH, FAR_HEIGHT,
                    current.video(), current.windowWidth(), current.windowHeight(), light, width, height);
            AmbientLightMath.toLinear(new int[]{pageColor}, pageLinear);
            final float toGridX = width / (float) current.windowWidth();
            final float toGridY = height / (float) current.windowHeight();
            float[] gridVideo = {current.video()[0] * toGridX, current.video()[1] * toGridY,
                    current.video()[2] * toGridX, current.video()[3] * toGridY};
            AmbientLightMath.soften(light, lightSoft, lightScratch, width, height, gridVideo, pageLinear);
            // Without the content of the page, as in fullscreen, there is no text to keep readable.
            // A cover would show as a darker frame around a fullscreen player that is pulled down.
            wantedCover = Settings.ENHANCED_AMBIENT_MODE_READABLE_TEXT.get()
                    ? SeeThroughPage.contentAlpha() * AmbientLightMath.readabilityCover(light, width, height,
                    current.video()[3] * height / current.windowHeight(), AmbientLightMath.luma(pageColor) < 128)
                    : 0f;
            final boolean coverMoved = stepCover();

            if (encodedLight.length != length) {
                encodedLight = new float[length];
                presentedLight = new float[length];
            }
            final float change = AmbientLightMath.encode(light, encodedLight, presentedLight, width, height, gridVideo);
            final boolean visible = current != drawnLayout || change >= MIN_VISIBLE_CHANGE;
            if (!visible && !coverMoved && !emptySurface) return;

            if (visible) {
                float[] shown = encodedLight;
                encodedLight = presentedLight;
                presentedLight = shown;
                updateLightBitmap(height);
            }
            litLayout = current;
            if (present(current)) {
                drawnLayout = current;
                if (!pageCleared) mainHandler.post(AmbientLightController::onFrameShown);
            } else {
                redraw = true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "process failure", ex);
        }
    }

    /**
     * Worker thread. The bitmap has more than 8 bits per channel, as even a blur over dozens
     * of pixels leaves visible color blotches once the light is rounded to 8 bits.
     */
    private static void updateLightBitmap(int height) {
        final int width = LIGHT_COLUMNS;
        // The window can still be drawing the last bitmap.
        final int index = nextLightBitmap;
        nextLightBitmap = 1 - index;
        Bitmap bitmap = lightBitmaps[index];
        if (bitmap == null || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16);
            lightBitmaps[index] = bitmap;
        }
        if (halfFloats.length != width * height * BITMAP_CHANNELS) {
            halfFloats = new short[width * height * BITMAP_CHANNELS];
            AmbientLightMath.fillAlpha(halfFloats);
        }
        AmbientLightMath.toHalfFloats(presentedLight, halfFloats);
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(halfFloats));
        // A shader keeps the pixels the bitmap had when the shader was created.
        lightPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        newestLightBitmap = bitmap;
    }

    /**
     * Main thread. Gives the window the newest light.
     */
    private static void showNewestLight() {
        if (!active) return;
        Bitmap bitmap = newestLightBitmap;
        if (bitmap != null) {
            // The shader goes on with the colors of its edge, also outside the window.
            windowShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            for (Paint paint : windowPaints) paint.setShader(windowShader);
            windowGridHeight = bitmap.getHeight();
        }
        invalidateWindowLight();
    }

    /**
     * Main thread. The light that the window draws has changed, or the window starts or stops drawing it.
     */
    static void invalidateWindowLight() {
        SeeThroughPage.invalidateLight();
        LightView windowLight = windowLightRef.get();
        if (windowLight != null) windowLight.invalidate();
        LightView playerLight = playerLightRef.get();
        if (playerLight != null) playerLight.invalidate();
    }

    /**
     * @return If the player is on its way to the miniplayer, or back from there.
     */
    static boolean isSlidingToMiniplayer() {
        return PlayerType.getCurrent() == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED;
    }

    /**
     * Main thread. While the window shows the light, draws the light of the window on a view of
     * the window, in the frame the view is drawn in.
     *
     * @param alpha How much of the view the light covers, from 0 to 1. The rest shows what is under the view.
     * @param covered The part of the window that is drawn again over this view, or null.
     * @return False if the surfaces show the light, and nothing was drawn.
     */
    static boolean drawWindowLight(Canvas canvas, View view, boolean onPage, float alpha, @Nullable Rect covered) {
        if (!veiled) return false;
        // Not before the first light of this video.
        if (alpha <= 0f || !pageCleared) return true;

        View root = rootRef.get();
        BitmapShader shader = windowShader;
        final int gridHeight = windowGridHeight;
        // The light of a window of another shape, as right after a rotation, cannot be shown.
        final boolean lit = root != null && shader != null && gridHeight != 0 && gridHeight == gridHeight(root);
        // A dimmed light shows what is under it. The bars of the player are not white on a light page.
        final int under = onPage || AmbientLightMath.luma(pageColor) < 128 ? pageColor : Color.BLACK;
        // The surfaces get the color, then the light at its strength, then the cover of the page.
        // That comes to the color with this much of the light over it.
        final float shown = strength / (float) FULL_STRENGTH;
        final int share = !lit ? 0 : Math.round(255 * (onPage ? shown * (1f - cover * shown) : shown));

        view.getLocationInWindow(drawLocation);
        Paint paint = windowColorPaint;
        if (share == 0) {
            paint.setColor(under);
        } else {
            // Every pixel that is filled twice costs time while the player moves, so the color
            // and the light go on in one fill: the filter turns the light into the two mixed.
            final int index = onPage ? 0 : 1;
            final int rest = 255 - share;
            final int mul = share << 16 | share << 8 | share;
            final int add = Color.red(under) * rest / 255 << 16 | Color.green(under) * rest / 255 << 8
                    | Color.blue(under) * rest / 255;
            paint = windowPaints[index];
            if (mul != windowFilterMul[index] || add != windowFilterAdd[index]) {
                windowFilterMul[index] = mul;
                windowFilterAdd[index] = add;
                paint.setColorFilter(share == 255 ? null : new LightingColorFilter(mul, add));
            }
            windowShaderMatrix.setScale(root.getWidth() / (float) LIGHT_COLUMNS, root.getHeight() / (float) gridHeight);
            windowShaderMatrix.postTranslate(-drawLocation[0], -drawLocation[1]);
            shader.setLocalMatrix(windowShaderMatrix);
        }
        paint.setAlpha(Math.round(255 * alpha));

        // Not where the video is, and not where the light is drawn again over this view: the
        // player draws its own light around the video.
        int holes = 0;
        Layout current = layout;
        LightView playerLight = playerLightRef.get();
        if (onPage && playerLight != null && SeeThroughPage.isDrawnPlain(playerLight, null)
                && playerLight.getGlobalVisibleRect(windowHoleBounds)) {
            holes = addWindowHole(holes, windowHoleBounds.left, windowHoleBounds.top, windowHoleBounds.right,
                    windowHoleBounds.bottom, view);
        } else if (current != null) {
            int[] video = current.video();
            holes = addWindowHole(holes, video[0], video[1], video[2], video[3], view);
        }
        if (covered != null) {
            holes = addWindowHole(holes, covered.left, covered.top, covered.right, covered.bottom, view);
        }
        int edges = 0;
        windowBands[edges++] = 0;
        windowBands[edges++] = view.getHeight();
        for (int i = 0; i < holes; i++) {
            windowBands[edges++] = windowHoles[i * 4 + 1];
            windowBands[edges++] = windowHoles[i * 4 + 3];
        }
        Arrays.sort(windowBands, 0, edges);
        // In the band between two edges a hole reaches from the top to the bottom, or it is not there.
        for (int band = 0; band + 1 < edges; band++) {
            final int top = windowBands[band];
            final int bottom = windowBands[band + 1];
            if (bottom <= top) continue;
            int left = 0;
            while (true) {
                int next = -1;
                for (int i = 0; i < holes; i++) {
                    if (windowHoles[i * 4 + 1] <= top && windowHoles[i * 4 + 3] >= bottom && windowHoles[i * 4 + 2] > left
                            && (next < 0 || windowHoles[i * 4] < windowHoles[next * 4])) {
                        next = i;
                    }
                }
                if (next < 0) break;
                if (windowHoles[next * 4] > left) canvas.drawRect(left, top, windowHoles[next * 4], bottom, paint);
                left = windowHoles[next * 4 + 2];
            }
            if (left < view.getWidth()) canvas.drawRect(left, top, view.getWidth(), bottom, paint);
        }
        return true;
    }

    /**
     * Notes a rectangle of the window that a view does not fill, in the coordinates of the view.
     * {@link #drawLocation} holds the place of the view.
     *
     * @return The number of rectangles noted.
     */
    private static int addWindowHole(int count, int left, int top, int right, int bottom, View view) {
        left = Math.max(0, left - drawLocation[0]);
        top = Math.max(0, top - drawLocation[1]);
        right = Math.min(view.getWidth(), right - drawLocation[0]);
        bottom = Math.min(view.getHeight(), bottom - drawLocation[1]);
        if (right <= left || bottom <= top) return count;
        windowHoles[count * 4] = left;
        windowHoles[count * 4 + 1] = top;
        windowHoles[count * 4 + 2] = right;
        windowHoles[count * 4 + 3] = bottom;
        return count + 1;
    }

    /**
     * Worker thread. Shows the current light at the current strength.
     *
     * @return False if some surface has no buffer yet.
     */
    private static boolean present(Layout current) {
        if (veiled) mainHandler.post(windowLightRunnable);
        final int buffers = surfaceBuffers;
        lightPaint.setAlpha(Math.min(255, strength * 255 / FULL_STRENGTH));
        final float scaleX = current.windowWidth() / (float) LIGHT_COLUMNS;
        final float scaleY = current.windowHeight() / (float) current.gridHeight();
        final int coverAlpha = Math.round(cover * strength * 255 / FULL_STRENGTH);
        final int coverColor = coverAlpha << 24 | (pageColor & 0xFFFFFF);
        // A dimmed light shows what is under it. The bars of the player are not white on a light page.
        final int barColor = AmbientLightMath.luma(pageColor) < 128 ? pageColor : Color.BLACK;
        boolean drawn = true;
        for (Region region : current.regions()) {
            drawn &= region.surface().draw(lightPaint, region.left(), region.top(), scaleX, scaleY,
                    LIGHT_COLUMNS, current.gridHeight(), region.onPage() ? pageColor : barColor,
                    region.onPage() ? coverColor : 0);
        }
        if (drawn && veiled && !current.regions().isEmpty()) {
            shownBuffers = buffers;
            shownLayout = current;
            mainHandler.post(unveilRunnable);
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

    private static void onInteraction(boolean scrolled) {
        if (!Settings.ENHANCED_AMBIENT_MODE_DIM_WHILE_SCROLLING.get()) return;

        final long now = SystemClock.uptimeMillis();
        if (now - lastInteraction < INTERACTION_THROTTLE_MS) return;
        final long restoreDelay = SeekBarPreference.clampToRange(Settings.ENHANCED_AMBIENT_MODE_DIM_DURATION) * 1_000L;
        // Without a delay, a touch that does not scroll would only make the light blink.
        if (restoreDelay == 0 && !scrolled) return;

        lastInteraction = now;
        setDimmed(true);
        mainHandler.removeCallbacks(restoreLight);
        mainHandler.postDelayed(restoreLight, Math.max(restoreDelay, SCROLL_END_MS));
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

        /**
         * Sees each touch as it passes, then lets it go on to the views below, which do their own
         * clicks. A view that takes touches in onTouchEvent would have to click itself as well.
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isOnPlayer(event)) {
                lastPageTouch = SystemClock.uptimeMillis();
                onInteraction(false);
            }
            // False, as this view is not clickable and has no touch listener.
            return super.dispatchTouchEvent(event);
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

    /**
     * On some devices the player holds two surfaces of the same size, and YouTube shows only one
     * of them. The other one is fully transparent and never gets a picture.
     *
     * @return If the surface is a better match for the video than the best one found so far.
     */
    private static boolean isBetterSurface(View surface, long area, View best) {
        final boolean shown = surface.getAlpha() > 0;
        if (shown != best.getAlpha() > 0) return shown;
        return area > (long) best.getWidth() * best.getHeight();
    }

    /**
     * @return If the player that was found before is still on screen.
     */
    private static boolean playerIsShown() {
        View video = videoRef.get();
        ViewGroup player = playerRef.get();
        return video != null && player != null && video.isShown() && childContaining(player, video) != null;
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
            if (contains && area > 0 && (best == null || isBetterSurface(view, area, best))) {
                best = view;
            }
        }
        if (view instanceof ViewGroup group) {
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
