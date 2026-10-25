/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController;
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController;
import app.morphe.extension.youtube.swipecontrols.controller.VolumeKeysController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.ClassicSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.PressToSwipeController;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.GestureController;
import app.morphe.extension.youtube.swipecontrols.misc.Rectangle;
import app.morphe.extension.youtube.swipecontrols.views.SwipeControlsOverlayLayout;

/**
 * The main controller for volume and brightness swipe controls.
 * Note that the superclass is overwritten to the superclass of the MainActivity at patch time.
 * <p>
 * The constructor of this class is never called, because the MainActivity constructor still calls
 * the constructor of its original superclass. Instance fields must not have initializers,
 * and are instead set in {@link #initialize()}.
 */
@SuppressWarnings("unused")
public class SwipeControlsHostActivity extends Activity {

    private static final String LOCK_MODE_OVERLAY_NAME = "player_overlay_lock_mode";

    /**
     * The currently active swipe controls host.
     * The reference may be null.
     */
    private static WeakReference<SwipeControlsHostActivity> currentHost = new WeakReference<>(null);

    /**
     * Container of the native lock screen overlay.
     * The reference may be null.
     */
    private static WeakReference<ViewGroup> lockModeOverlay = new WeakReference<>(null);

    /**
     * Current instance of {@link AudioVolumeController}.
     */
    @Nullable
    private AudioVolumeController audio;

    /**
     * Current instance of {@link ScreenBrightnessController}.
     */
    @Nullable
    private ScreenBrightnessController screen;

    /**
     * Current instance of {@link SwipeControlsConfigurationProvider}.
     */
    private SwipeControlsConfigurationProvider config;

    /**
     * Current instance of {@link SwipeControlsOverlayLayout}.
     */
    private SwipeControlsOverlayLayout overlay;

    /**
     * Current instance of {@link SwipeZonesController}.
     */
    private SwipeZonesController zones;

    /**
     * Main gesture controller.
     */
    private GestureController gesture;

    /**
     * Main volume keys controller.
     */
    private VolumeKeysController keys;

    /**
     * Listener for changes in the player type.
     */
    private Consumer<PlayerType> playerTypeObserver;

    /**
     * Whether the status bar is visible on Android 15+ (edge-to-edge display).
     */
    private volatile boolean statusBarVisible;

    // Flag that indicates whether the brightness has been saved and restored default brightness
    private boolean isBrightnessSaved;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialize();
    }

    @Override
    protected void onStart() {
        super.onStart();
        reAttachOverlays();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PlayerType.getOnChange().removeObserver(playerTypeObserver);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        ensureInitialized();
        if (gesture.submitTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        ensureInitialized();
        if (keys.onKeyEvent(ev)) {
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /**
     * Dispatches a touch event to downstream views.
     *
     * @param event The event to dispatch.
     * @return Whether the event was consumed.
     */
    public boolean dispatchDownstreamTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    @Nullable
    public AudioVolumeController getAudio() {
        return audio;
    }

    @Nullable
    public ScreenBrightnessController getScreen() {
        return screen;
    }

    public SwipeControlsConfigurationProvider getConfig() {
        return config;
    }

    public SwipeControlsOverlayLayout getOverlay() {
        return overlay;
    }

    public SwipeZonesController getZones() {
        return zones;
    }

    public boolean isStatusBarVisible() {
        return statusBarVisible;
    }

    /**
     * Current content view with id {@link android.R.id#content}.
     */
    private ViewGroup getContentRoot() {
        return getWindow().getDecorView().findViewById(android.R.id.content);
    }

    /**
     * Ensures that swipe controllers are initialized and attached.
     * On some ROMs with SDK <= 23, {@link #onCreate} and {@link #onStart} may not be called correctly.
     * See https://github.com/revanced/revanced-patches/issues/446
     */
    private void ensureInitialized() {
        if (config == null) {
            Logger.printException(() -> "swipe controls were not initialized in onCreate, "
                    + "initializing on-the-fly (SDK is " + Build.VERSION.SDK_INT + ")");
            initialize();
            reAttachOverlays();
        }
    }

    /**
     * Initializes controllers, only call once.
     */
    private void initialize() {
        // create controllers
        Logger.printDebug(() -> "initializing swipe controls controllers");
        config = new SwipeControlsConfigurationProvider();
        keys = new VolumeKeysController(this);
        audio = createAudioController();
        screen = createScreenController();

        // create overlay
        overlay = new SwipeControlsOverlayLayout(this, config);
        getContentRoot().addView(overlay);

        // create swipe zone controller
        zones = new SwipeZonesController(this, () -> {
            ViewGroup contentRoot = getContentRoot();
            return new Rectangle(
                    (int) contentRoot.getX(),
                    (int) contentRoot.getY(),
                    contentRoot.getWidth(),
                    contentRoot.getHeight()
            );
        });

        // create the gesture controller
        gesture = createGestureController();

        // listen for changes in the player type
        playerTypeObserver = this::onPlayerTypeChanged;
        PlayerType.getOnChange().addObserver(playerTypeObserver);

        // set current instance reference
        currentHost = new WeakReference<>(this);

        // fix edge-to-edge display
        // see: https://github.com/MorpheApp/morphe-patches/issues/658
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewParent rootView = getContentRoot().getParent();
            if (rootView instanceof ViewGroup viewGroup) {
                viewGroup.setOnApplyWindowInsetsListener((v, insets) -> {
                    statusBarVisible = insets.isVisible(WindowInsets.Type.statusBars());
                    return insets;
                });
            }
        }
    }

    /**
     * Re-attaches swipe overlays.
     */
    private void reAttachOverlays() {
        Logger.printDebug(() -> "attaching swipe controls overlay");
        ViewGroup contentRoot = getContentRoot();
        contentRoot.removeView(overlay);
        contentRoot.addView(overlay);
    }

    /**
     * If the app shares the screen with another app, which the player of a fold device is used
     * with the same way it is used in fullscreen.
     * <p>
     * Picture in picture is a multi window mode as well and is not one of these. The window of
     * the app is pinned then, and the player it returns to is the maximized one.
     */
    public boolean isInSplitScreenMode() {
        return isInMultiWindowMode() && !isInPictureInPictureMode();
    }

    /**
     * Called when the player type changes.
     *
     * @param type The new player type.
     */
    private void onPlayerTypeChanged(PlayerType type) {
        if (config.shouldSaveAndRestoreBrightness()
                && (type == PlayerType.WATCH_WHILE_FULLSCREEN
                || (isInSplitScreenMode() && type == PlayerType.WATCH_WHILE_MAXIMIZED))
                && isBrightnessSaved) {
            // If saving and restoring brightness is enabled, and the player type is fullscreen or multi-window,
            // and brightness has already been saved, then restore the screen brightness
            if (screen != null) {
                screen.restore();
            }
            isBrightnessSaved = false;
        } else if (config.shouldSaveAndRestoreBrightness() && !isBrightnessSaved) {
            // If saving and restoring brightness is enabled, and brightness has not been saved,
            // then save the current screen state, restore default brightness, and mark brightness as saved
            if (screen != null) {
                screen.save();
                screen.restoreDefaultBrightness();
            }
            isBrightnessSaved = true;
        } else if (screen != null) {
            // If saving and restoring brightness is disabled, simply keep the default brightness
            screen.restoreDefaultBrightness();
        }
    }

    /**
     * Creates the audio volume controller.
     */
    @Nullable
    private AudioVolumeController createAudioController() {
        if (!config.isVolumeControlEnabled()) {
            return null;
        }
        AudioVolumeController controller = new AudioVolumeController(this);
        return controller.isAvailable() ? controller : null;
    }

    /**
     * Creates the screen brightness controller instance.
     */
    @Nullable
    private ScreenBrightnessController createScreenController() {
        return config.isBrightnessControlEnabled()
                ? new ScreenBrightnessController(this)
                : null;
    }

    /**
     * Creates the gesture controller based on settings.
     */
    private GestureController createGestureController() {
        return config.shouldEnablePressToSwipe()
                ? new PressToSwipeController(this)
                : new ClassicSwipeController(this);
    }

    /**
     * The currently active swipe controls host.
     * The reference may be null.
     */
    public static WeakReference<SwipeControlsHostActivity> getCurrentHost() {
        return currentHost;
    }

    /**
     * Whether the native lock screen is currently engaged.
     * <p>
     * The container is attached for the entire playback session, and only its Litho content
     * is mounted while the screen is locked, so the mounted content is what tells both states apart.
     */
    public static boolean isPlayerLocked() {
        ViewGroup overlay = lockModeOverlay.get();
        if (overlay == null) return false;

        for (int i = 0, count = overlay.getChildCount(); i < count; i++) {
            View child = overlay.getChildAt(i);
            if (child instanceof ViewGroup group && group.getChildCount() > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static void setPlayerOverlay(View overlay, @Nullable String overlayName) {
        // The same container class wraps every player overlay, and only the name tells them apart.
        if (LOCK_MODE_OVERLAY_NAME.equals(overlayName) && overlay instanceof ViewGroup group) {
            lockModeOverlay = new WeakReference<>(group);
        }
    }

    /**
     * Injection point.
     */
    public static boolean allowSwipeChangeVideo(boolean original) {
        // Feature can cause crashing if forced in newer targets.
        return !VersionCheckPatch.IS_20_22_OR_GREATER && Settings.SWIPE_CHANGE_VIDEO.get();
    }
}
