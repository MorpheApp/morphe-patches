/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.holdgesture.HoldGesture.Adjust;
import app.morphe.extension.youtube.holdgesture.HoldGesture.Outcome;
import app.morphe.extension.youtube.holdgesture.HoldGesture.State;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.CustomPlaybackSpeedPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerControlsVisibility;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.AnySwipeZoneAvailability;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;

/**
 * Turns YouTube's "tap and hold for 2x" into a gesture that goes on after the long press:
 * a drag to the side changes the speed, a drag down keeps the speed after the release,
 * and a drag up opens a launcher of actions.
 * <p>
 * YouTube still decides when a long press on the player begins. From then on this class takes
 * the touch away from the app until the finger is lifted.
 */
@SuppressWarnings("unused")
public final class HoldGestureController implements HoldGesture.Listener, LauncherAction.Host {

    public enum SpeedStep {
        STEP_005(0.05f),
        STEP_010(0.1f),
        STEP_025(0.25f),
        STEP_050(0.5f);

        final float speed;

        SpeedStep(float speed) {
            this.speed = speed;
        }
    }

    public enum Sensitivity {
        LOW(26),
        NORMAL(18),
        HIGH(12);

        /** Distance in dp that the finger moves for one speed step. */
        final float stepDistance;

        Sensitivity(float stepDistance) {
            this.stepDistance = stepDistance;
        }
    }

    /**
     * A slot of the launcher can be set while the launcher has that many slots.
     */
    public static final class LauncherSlotAvailability implements Setting.Availability {
        private final int slot;

        public LauncherSlotAvailability(int slot) {
            this.slot = slot;
        }

        @Override
        public boolean isAvailable() {
            return Settings.HOLD_GESTURE.get() && Settings.HOLD_GESTURE_LAUNCHER.get()
                    && Settings.HOLD_GESTURE_LAUNCHER_SLOT_COUNT.get() >= slot;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.HOLD_GESTURE, Settings.HOLD_GESTURE_LAUNCHER,
                    Settings.HOLD_GESTURE_LAUNCHER_SLOT_COUNT);
        }
    }

    private static final float SLOWEST_SPEED = 0.1f;
    private static final long LOCK_FLASH_MS = 1500;
    private static final long UNLOCK_FLASH_MS = 1200;
    /** How long after a new video starts a locked speed is put back when something else changes it. */
    private static final long KEEP_LOCK_AFTER_VIDEO_CHANGE_MS = 5000;
    private static final float BADGE_ALPHA_WITHOUT_CONTROLS = 0.55f;
    private static final int LAUNCHER_FEWEST_SLOTS = 3;
    private static final List<EnumSetting<LauncherAction>> LAUNCHER_SLOTS = List.of(
            Settings.HOLD_GESTURE_LAUNCHER_SLOT_1, Settings.HOLD_GESTURE_LAUNCHER_SLOT_2,
            Settings.HOLD_GESTURE_LAUNCHER_SLOT_3, Settings.HOLD_GESTURE_LAUNCHER_SLOT_4,
            Settings.HOLD_GESTURE_LAUNCHER_SLOT_5);
    private static final long UNLOCK_AFTER_LAUNCHER_MS = 400;

    private static final HoldGestureController instance = new HoldGestureController();

    private WeakReference<HoldGestureOverlayView> overlayRef = new WeakReference<>(null);
    private WeakReference<HoldLauncherView> launcherRef = new WeakReference<>(null);
    private WeakReference<Window> windowRef = new WeakReference<>(null);
    @Nullable
    private WindowTouchInterceptor interceptor;
    private boolean observersRegistered;

    private boolean fingerDown;
    private int fingerId;
    private long fingerDownTime;
    /** Position of the finger in the window, in pixels. */
    private float fingerX;
    private float fingerY;
    private boolean badgePressed;

    /** Set from the long press until the finger is lifted. The app gets no touches meanwhile. */
    private boolean ownsTouch;
    @Nullable
    private HoldGesture gesture;
    private boolean lockArmed;
    private boolean toldAboutPressToSwipe;

    /** Where the long press was, in the window, in dp. */
    private float pressX;
    private float pressY;
    private LauncherAction[] launcherActions = new LauncherAction[0];
    /** Null until the launcher is open. */
    @Nullable
    private RadialLayout launcherLayout;
    private int launcherSelected = -1;

    /** While set, the app gets no touches at all, and only the unlock button ends that. */
    private boolean screenLocked;
    private boolean unlockPressed;
    @Nullable
    private Object backBlocker;
    private final Runnable tickGesture = this::tickGesture;

    /** Speed to go back to when the hold ends without a lock. */
    private float speedBeforeHold;
    /** 0 while no speed is locked. */
    private float lockedSpeed;
    private float speedBeforeLock;
    private long keepLockUntil;
    private long flashUntil;
    private boolean flashIsUnlock;
    private float flashSpeed;

    private HoldGestureController() {
    }

    // region injection points

    /**
     * Injection point. Called every time YouTube builds the layout of the player.
     */
    public static void initialize(ViewGroup overlayRoot) {
        try {
            if (!Settings.HOLD_GESTURE.get()) return;

            instance.attach(overlayRoot);
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Injection point. Called when a long press on the player is reached
     * and the app is about to play faster while the press lasts.
     *
     * @return Whether the gesture took over, and the app should do nothing for this long press.
     */
    public static boolean onTapAndHold() {
        try {
            if (!Settings.HOLD_GESTURE.get()) return false;

            Logger.printDebug(() -> "Long press with tap and hold of the app");
            return instance.begin();
        } catch (Exception ex) {
            Logger.printException(() -> "onTapAndHold failure", ex);
            return false;
        }
    }

    // endregion

    private void attach(ViewGroup overlayRoot) {
        final Activity activity = Utils.getActivity();
        if (activity == null) return;

        final Window window = activity.getWindow();
        if (windowRef.get() != window) {
            windowRef = new WeakReference<>(window);
            interceptor = WindowTouchInterceptor.install(window, this::onWindowTouchEvent, this::onWindowKeyEvent);
            if (screenLocked) {
                // The back gesture is kept from the activity that is gone.
                blockBack(activity);
            }
        }
        if (gesture != null && launcherLayout != null) {
            // The launcher is laid out for the player layout that is replaced now.
            gesture.cancel();
        }

        final HoldGestureOverlayView previous = overlayRef.get();
        if (previous != null && previous.getParent() instanceof ViewGroup parent) {
            parent.removeView(previous);
        }
        final HoldGestureOverlayView overlay = new HoldGestureOverlayView(overlayRoot.getContext());
        overlayRoot.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayRef = new WeakReference<>(overlay);

        final HoldLauncherView previousLauncher = launcherRef.get();
        if (previousLauncher != null && previousLauncher.getParent() instanceof ViewGroup parent) {
            parent.removeView(previousLauncher);
        }
        final HoldLauncherView launcher = new HoldLauncherView(overlayRoot.getContext());
        overlayRoot.addView(launcher, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        launcherRef = new WeakReference<>(launcher);

        if (!observersRegistered) {
            observersRegistered = true;
            PlayerType.getOnChange().addObserver(type ->
                    Utils.runOnMainThreadNowOrLater(() -> playerTypeChanged(type)));
            PlayerControlsVisibility.getOnChange().addObserver(visibility ->
                    Utils.runOnMainThreadNowOrLater(this::updateBadge));
            VideoInformation.onPlaybackSpeedChange.addObserver(speed ->
                    Utils.runOnMainThreadNowOrLater(() -> playbackSpeedChanged(speed)));
            VideoInformation.onVideoIdChange.addObserver(videoId ->
                    Utils.runOnMainThreadNowOrLater(this::videoChanged));
        }
        overlay.setFullscreen(PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN);
        updateBadge();
    }

    // region touch

    private boolean onWindowTouchEvent(WindowTouchInterceptor source, MotionEvent event) {
        try {
            // An interceptor of an earlier window can still be in a chain of callbacks.
            if (source != interceptor) return false;

            return handleTouch(event);
        } catch (Exception ex) {
            Logger.printException(() -> "onWindowTouchEvent failure", ex);
            return false;
        }
    }

    private boolean onWindowKeyEvent(WindowTouchInterceptor source, KeyEvent event) {
        if (source != interceptor || !screenLocked || event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;

        if (event.getAction() == KeyEvent.ACTION_UP) {
            onBackWhileLocked();
        }
        return true;
    }

    private boolean handleTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (screenLocked) {
            handleLockedTouch(event);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            fingerDown = true;
            fingerId = event.getPointerId(0);
            fingerDownTime = event.getDownTime();
            fingerX = event.getX();
            fingerY = event.getY();
            ownsTouch = false;
            badgePressed = isOnBadge();
            return badgePressed;
        }
        if (!fingerDown) return false;

        final int index = event.findPointerIndex(fingerId);
        if (index >= 0) {
            fingerX = event.getX(index);
            fingerY = event.getY(index);
        }
        final boolean lifted = action == MotionEvent.ACTION_UP
                || (action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == index);
        final boolean ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (ended) {
            fingerDown = false;
        }

        if (badgePressed) {
            if (action == MotionEvent.ACTION_UP && isOnBadge()) {
                unlock();
            }
            if (ended) {
                badgePressed = false;
            }
            return true;
        }
        if (!ownsTouch) return false;

        if (gesture != null) {
            if (lifted) {
                gesture.release();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                gesture.cancel();
            } else if (action == MotionEvent.ACTION_MOVE) {
                final float density = density();
                gesture.move(fingerX / density, fingerY / density, event.getEventTime());
            }
        }
        if (ended) {
            ownsTouch = false;
        }
        return true;
    }

    private boolean isOnBadge() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay == null || !overlay.isAttachedToWindow()) return false;

        final int[] location = new int[2];
        overlay.getLocationInWindow(location);
        return overlay.isOnBadge(fingerX - location[0], fingerY - location[1]);
    }

    private boolean isOnUnlock() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay == null || !overlay.isAttachedToWindow()) return false;

        final int[] location = new int[2];
        overlay.getLocationInWindow(location);
        return overlay.isOnUnlock(fingerX - location[0], fingerY - location[1]);
    }

    /**
     * Ends the touch for the views of the app, which saw it begin and now get nothing more of it.
     */
    private void cancelTouchForApp() {
        final WindowTouchInterceptor current = interceptor;
        if (current == null) return;

        final MotionEvent cancel = MotionEvent.obtain(fingerDownTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, fingerX, fingerY, 0);
        cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        current.dispatchToViews(cancel);
        cancel.recycle();
    }

    // endregion

    // region gesture

    /**
     * @return Whether the gesture took over the long press.
     */
    private boolean begin() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (pressToSwipeUsesLongPress()) {
            tellAboutPressToSwipe();
            return false;
        }
        if (overlay == null || !overlay.isAttachedToWindow() || !fingerDown || badgePressed
                || gesture != null || ShortsPlayerState.isOpen()) {
            Logger.printDebug(() -> "Long press left to the app. Overlay attached: "
                    + (overlay != null && overlay.isAttachedToWindow()) + ", finger down: " + fingerDown
                    + ", on badge: " + badgePressed + ", in a gesture: " + (gesture != null));
            return false;
        }

        final SpeedStep step = Settings.HOLD_GESTURE_SPEED_STEP.get();
        final float maxSpeed = Utils.clamp(Settings.HOLD_GESTURE_SPEED_MAX.get(),
                SLOWEST_SPEED, VideoInformation.PLAYBACK_SPEED_MAXIMUM);
        final float minSpeed = Utils.clamp(Settings.HOLD_GESTURE_SPEED_MIN.get(), SLOWEST_SPEED, maxSpeed);
        launcherActions = launcherActions();
        final HoldGesture.Config config = new HoldGesture.Config(
                Settings.HOLD_GESTURE_SPEED_SWIPE.get(),
                Settings.HOLD_GESTURE_LOCK.get(),
                launcherActions.length > 0,
                step.speed, minSpeed, maxSpeed,
                Settings.HOLD_GESTURE_SPEED_SENSITIVITY.get().stepDistance);

        final boolean locked = lockedSpeed > 0;
        speedBeforeHold = VideoInformation.getPlaybackSpeed();
        final float holdSpeed = locked ? lockedSpeed : CustomPlaybackSpeedPatch.getTapAndHoldSpeed();

        final float density = density();
        final View root = overlay.getRootView();
        final HoldGesture.Room room = new HoldGesture.Room(fingerX / density,
                (root.getWidth() - fingerX) / density, (root.getHeight() - fingerY) / density);

        ownsTouch = true;
        lockArmed = false;
        flashUntil = 0;
        pressX = fingerX / density;
        pressY = fingerY / density;
        launcherLayout = null;
        launcherSelected = -1;
        overlay.setSpeedRange(step.speed, minSpeed, maxSpeed);
        overlay.post(this::cancelTouchForApp);

        Logger.printDebug(() -> "Hold begins at " + holdSpeed + "x, speed before: " + speedBeforeHold
                + "x, locked: " + locked);
        gesture = new HoldGesture(config, this);
        gesture.begin(pressX, pressY, room, holdSpeed, locked);
        return true;
    }

    /**
     * The swipe controls can start their swipes with a long press too ("press to swipe").
     * The long press is theirs then, in every state of the player, so that one rule holds:
     * the hold gesture is not used while press to swipe is on.
     */
    private static boolean pressToSwipeUsesLongPress() {
        final SwipeControlsHostActivity host = SwipeControlsHostActivity.getCurrentHost().get();
        if (host == null) return false;

        // What the swipe controls use since the app started, which is what the setting was then.
        final SwipeControlsConfigurationProvider config = host.getConfig();
        return config != null && config.shouldEnablePressToSwipe() && hasSwipeAction();
    }

    private static boolean hasSwipeAction() {
        return Settings.SWIPE_SEEK.get() || new AnySwipeZoneAvailability().isAvailable();
    }

    /**
     * Without a word, the hold gesture would look broken to someone who turned it on.
     * Once for each start of the app is enough.
     */
    private void tellAboutPressToSwipe() {
        if (toldAboutPressToSwipe) return;

        toldAboutPressToSwipe = true;
        Utils.showToastLong(str("morphe_hold_gesture_press_to_swipe_toast"));
    }

    /**
     * @return Whether the settings have the swipe controls wait for a long press,
     *         which takes effect with the next start of the app.
     */
    public static boolean isPressToSwipeOn() {
        return Settings.SWIPE_PRESS_TO_ENGAGE.get() && hasSwipeAction();
    }

    @Override
    public void onStateChanged(State previous, State current) {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay == null) return;

        switch (current) {
            case HOLD_SPEED -> overlay.hideLockTarget();
            case SPEED_ADJUST -> {
                overlay.showRuler();
                overlay.hideLockTarget();
                overlay.removeCallbacks(tickGesture);
                overlay.postOnAnimation(tickGesture);
            }
            case LOCK_TARGET -> overlay.showLockTarget();
            case LAUNCHER -> {
                // The launcher is for the video as it plays without the hold.
                overlay.hidePill();
                applySpeed(speedBeforeHold);
            }
            case IDLE -> {
                overlay.hidePill();
                overlay.hideLockTarget();
            }
        }
        updateBadge();
    }

    /**
     * Part of a sideways drag goes on while the finger rests, so the gesture is asked every frame.
     */
    private void tickGesture() {
        final HoldGesture current = gesture;
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (current == null || overlay == null || current.state() != State.SPEED_ADJUST) return;

        // The same clock as the times of the touch events.
        current.tick(SystemClock.uptimeMillis());
        if (gesture == current && current.state() == State.SPEED_ADJUST) {
            overlay.postOnAnimation(tickGesture);
        }
    }

    @Override
    public void onAdjustChanged(Adjust adjust, int direction) {
        Logger.printDebug(() -> "Sideways drag: " + adjust + ", direction: " + direction);
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.setAdjust(adjust, direction);
        }
        if (adjust == Adjust.THROTTLE) {
            // Stronger than the click of a step, like getting over an edge.
            haptic(HapticFeedbackConstants.CONTEXT_CLICK);
        }
    }

    @Override
    public void onNeutralMoved() {
        Logger.printDebug(() -> "Neutral point moved to the finger");
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public void onSpeedChanged(float speed, boolean stepped) {
        applySpeed(speed);
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            if (stepped) {
                overlay.setSpeed(speed);
            } else {
                overlay.showPill(speed);
            }
        }
        haptic(stepped ? HapticFeedbackConstants.CLOCK_TICK : HapticFeedbackConstants.LONG_PRESS);
    }

    @Override
    public void onLockProgress(float progress, boolean armed) {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.setLockProgress(progress, armed);
        }
        if (armed != lockArmed) {
            lockArmed = armed;
            haptic(armed ? confirmHaptic() : HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    @Override
    public void onLauncherMoved(float x, float y) {
        final HoldLauncherView launcher = launcherRef.get();
        if (launcher == null || !launcher.isAttachedToWindow()) return;

        // The launcher is laid out in the coordinates of its view, which is the player.
        final float density = density();
        final int[] location = new int[2];
        launcher.getLocationInWindow(location);
        final float left = location[0] / density;
        final float top = location[1] / density;
        final float fingerInViewX = x - left;
        final float fingerInViewY = y - top;

        RadialLayout layout = launcherLayout;
        if (layout == null) {
            final RectF area = launcher.safeArea();
            layout = RadialLayout.create(pressX - left, pressY - top, fingerInViewX, fingerInViewY,
                    area.left, area.top, area.right, area.bottom, launcherActions.length);
            launcherLayout = layout;
            final HoldLauncherView.Item[] items = new HoldLauncherView.Item[launcherActions.length];
            for (int i = 0; i < items.length; i++) {
                final LauncherAction action = launcherActions[i];
                items[i] = new HoldLauncherView.Item(new LauncherIcon(action), action.label(), action.isOn());
            }
            Logger.printDebug(() -> "Launcher opens with: " + List.of(launcherActions));
            launcher.open(layout, items, fingerInViewX, fingerInViewY);
            haptic(HapticFeedbackConstants.KEYBOARD_TAP);
        }

        final int selected = layout.select(fingerInViewX, fingerInViewY, launcherSelected);
        if (selected != launcherSelected) {
            launcherSelected = selected;
            Logger.printDebug(() -> "Launcher points at: " + (selected < 0 ? "nothing" : launcherActions[selected]));
            if (selected >= 0) {
                haptic(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        launcher.setPointer(fingerInViewX, fingerInViewY, selected);
    }

    @Override
    public void onFinished(Outcome outcome, float speed) {
        Logger.printDebug(() -> "Hold ends: " + outcome + " at " + speed + "x");
        gesture = null;
        final LauncherAction picked = outcome == Outcome.LAUNCHER && launcherSelected >= 0
                ? launcherActions[launcherSelected]
                : null;
        if (launcherLayout != null) {
            launcherLayout = null;
            launcherSelected = -1;
            final HoldLauncherView launcher = launcherRef.get();
            if (launcher != null) {
                launcher.close(picked != null);
            }
        }
        switch (outcome) {
            case RESTORE, LAUNCHER -> applySpeed(speedBeforeHold);
            case LOCK -> lock(speed);
            case UNLOCK -> unlock();
        }
        updateBadge();

        if (picked != null) {
            haptic(confirmHaptic());
            // After the touch is done with, so that nothing an action does gets in its way.
            Utils.runOnMainThread(() -> runLauncherAction(picked));
        }
    }

    // endregion

    // region launcher and screen lock

    /**
     * @return The actions of the slots that are in use, each one once, without those of patches
     *         that were not used on the app. Empty if the launcher is off.
     */
    private static LauncherAction[] launcherActions() {
        if (!Settings.HOLD_GESTURE_LAUNCHER.get()) return new LauncherAction[0];

        final int count = Utils.clamp(Settings.HOLD_GESTURE_LAUNCHER_SLOT_COUNT.get(),
                LAUNCHER_FEWEST_SLOTS, LAUNCHER_SLOTS.size());
        final List<LauncherAction> actions = new ArrayList<>(count);
        for (EnumSetting<LauncherAction> slot : LAUNCHER_SLOTS.subList(0, count)) {
            final LauncherAction action = slot.get();
            if (action.isAvailable() && !actions.contains(action)) {
                actions.add(action);
            }
        }
        return actions.toArray(new LauncherAction[0]);
    }

    private void runLauncherAction(LauncherAction action) {
        try {
            Logger.printDebug(() -> "Launcher runs: " + action);
            action.run(this);
        } catch (Exception ex) {
            Logger.printException(() -> "runLauncherAction failure", ex);
        }
    }

    @Override
    public View playerView() {
        return overlayRef.get();
    }

    @Override
    public void flashShutter() {
        final HoldLauncherView launcher = launcherRef.get();
        if (launcher != null) {
            launcher.flash();
        }
    }

    @Override
    public void lockScreen() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        // The way out of the lock is a button on the player.
        if (screenLocked || overlay == null || !overlay.isAttachedToWindow()
                || !PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return;
        }

        Logger.printDebug(() -> "Screen locked");
        screenLocked = true;
        unlockPressed = false;
        // Once the launcher is gone, which the unlock button would show through.
        overlay.postDelayed(() -> {
            if (screenLocked) {
                overlay.showUnlock();
            }
        }, UNLOCK_AFTER_LAUNCHER_MS);
        blockBack(Utils.getActivity());
    }

    private void blockBack(@Nullable Activity activity) {
        releaseBack();
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backBlocker = BackBlocker.block(activity, this::onBackWhileLocked);
        }
    }

    private void releaseBack() {
        if (backBlocker != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BackBlocker.release(backBlocker);
        }
        backBlocker = null;
    }

    private void unlockScreen() {
        if (!screenLocked) return;

        Logger.printDebug(() -> "Screen unlocked");
        screenLocked = false;
        unlockPressed = false;
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.hideUnlock();
        }
        releaseBack();
        haptic(confirmHaptic());
    }

    /**
     * Every touch on a locked screen shows the button that unlocks it. A tap on that button unlocks.
     */
    private void handleLockedTouch(MotionEvent event) {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay == null || !overlay.isAttachedToWindow()) {
            // Without the player there is no button to get out with.
            unlockScreen();
            return;
        }

        final int action = event.getActionMasked();
        fingerX = event.getX();
        fingerY = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            unlockPressed = isOnUnlock();
            overlay.showUnlock();
            overlay.setUnlockPressed(unlockPressed);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean unlock = action == MotionEvent.ACTION_UP && unlockPressed && isOnUnlock();
            unlockPressed = false;
            overlay.setUnlockPressed(false);
            if (unlock) {
                unlockScreen();
            }
        }
    }

    private void onBackWhileLocked() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.showUnlock();
        }
    }

    /**
     * Keeps the back gesture from the app on Android versions where it does not come as a key.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class BackBlocker {
        private final OnBackInvokedDispatcher dispatcher;
        private final OnBackInvokedCallback callback;

        private BackBlocker(OnBackInvokedDispatcher dispatcher, OnBackInvokedCallback callback) {
            this.dispatcher = dispatcher;
            this.callback = callback;
        }

        static Object block(Activity activity, Runnable onBack) {
            final OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            final OnBackInvokedCallback callback = onBack::run;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            return new BackBlocker(dispatcher, callback);
        }

        static void release(Object blocker) {
            if (blocker instanceof BackBlocker backBlocker) {
                backBlocker.dispatcher.unregisterOnBackInvokedCallback(backBlocker.callback);
            }
        }
    }

    // endregion

    // region speed and lock

    private void applySpeed(float speed) {
        if (VideoInformation.getPlaybackSpeed() != speed) {
            Logger.printDebug(() -> "Changing speed to " + speed + "x");
            VideoInformation.changePlaybackSpeed(speed);
        }
    }

    private void lock(float speed) {
        if (lockedSpeed <= 0) {
            speedBeforeLock = speedBeforeHold;
        }
        if (speed == speedBeforeLock) {
            // Locking the speed that the lock would go back to is the same as no lock.
            unlock();
            return;
        }
        lockedSpeed = speed;
        applySpeed(speed);
        flash(false, speed, LOCK_FLASH_MS);
    }

    private void unlock() {
        if (lockedSpeed <= 0) {
            applySpeed(speedBeforeHold);
            return;
        }
        lockedSpeed = 0;
        applySpeed(speedBeforeLock);
        haptic(confirmHaptic());
        flash(true, speedBeforeLock, UNLOCK_FLASH_MS);
    }

    private void playbackSpeedChanged(float speed) {
        if (lockedSpeed <= 0 || ownsTouch || speed == lockedSpeed) return;

        if (SystemClock.uptimeMillis() < keepLockUntil) {
            // The app or another patch set the speed of the new video.
            applySpeed(lockedSpeed);
        } else {
            // The speed was picked somewhere else, such as in the speed menu. That ends the lock.
            Logger.printDebug(() -> "Lock ended by a speed change to " + speed + "x");
            lockedSpeed = 0;
            updateBadge();
        }
    }

    private void videoChanged() {
        if (lockedSpeed <= 0) return;

        Logger.printDebug(() -> "Video changed while locked at " + lockedSpeed + "x, speed now: "
                + VideoInformation.getPlaybackSpeed() + "x");
        if (Settings.HOLD_GESTURE_UNLOCK_ON_VIDEO_CHANGE.get()) {
            final float locked = lockedSpeed;
            lockedSpeed = 0;
            // Nothing to do if the new video already got its own speed.
            if (VideoInformation.getPlaybackSpeed() == locked) {
                applySpeed(speedBeforeLock);
            }
            updateBadge();
        } else {
            keepLockUntil = SystemClock.uptimeMillis() + KEEP_LOCK_AFTER_VIDEO_CHANGE_MS;
            applySpeed(lockedSpeed);
        }
    }

    private void playerTypeChanged(PlayerType type) {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.setFullscreen(type == PlayerType.WATCH_WHILE_FULLSCREEN);
        }
        if (!type.isMaximizedOrFullscreen()) {
            if (gesture != null) {
                gesture.cancel();
            }
            // The unlock button is on the player.
            unlockScreen();
        }
    }

    // endregion

    // region badge and feedback

    private void flash(boolean unlock, float speed, long duration) {
        flashIsUnlock = unlock;
        flashSpeed = speed;
        flashUntil = SystemClock.uptimeMillis() + duration;
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.postDelayed(this::updateBadge, duration);
        }
        updateBadge();
    }

    private void updateBadge() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay == null) return;

        final PlayerControlsVisibility controls = PlayerControlsVisibility.getCurrent();
        final boolean controlsShown = controls == PlayerControlsVisibility.PLAYER_CONTROLS_VISIBILITY_SHOWN
                || controls == PlayerControlsVisibility.PLAYER_CONTROLS_VISIBILITY_WILL_SHOW;
        final boolean flashing = SystemClock.uptimeMillis() < flashUntil;
        if (gesture != null) {
            overlay.setBadge(0, true, 0);
        } else if (flashing && flashIsUnlock) {
            overlay.setBadge(flashSpeed, false, 1);
        } else if (lockedSpeed > 0) {
            final float alpha = flashing || controlsShown ? 1
                    : Settings.HOLD_GESTURE_LOCK_BADGE_ALWAYS.get() ? BADGE_ALPHA_WITHOUT_CONTROLS : 0;
            overlay.setBadge(lockedSpeed, true, alpha);
        } else {
            overlay.setBadge(0, true, 0);
        }
    }

    @SuppressWarnings("deprecation")
    private void haptic(int constant) {
        if (!Settings.HOLD_GESTURE_HAPTIC_FEEDBACK.get()) return;

        final HoldGestureOverlayView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private static int confirmHaptic() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.VIRTUAL_KEY;
    }

    // endregion

    private float density() {
        final HoldGestureOverlayView overlay = overlayRef.get();
        return overlay == null ? 1 : overlay.getResources().getDisplayMetrics().density;
    }
}
