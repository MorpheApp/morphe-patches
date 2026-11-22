/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
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
 * a drag to the side changes the speed, and a drag down keeps the speed after the release.
 * <p>
 * YouTube still decides when a long press on the player begins. From then on this class takes
 * the touch away from the app until the finger is lifted.
 */
@SuppressWarnings("unused")
public final class HoldGestureController implements HoldGesture.Listener {

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

    private static final float SLOWEST_SPEED = 0.1f;
    private static final long LOCK_FLASH_MS = 1500;
    private static final long UNLOCK_FLASH_MS = 1200;
    /** How long after a new video starts a locked speed is put back when something else changes it. */
    private static final long KEEP_LOCK_AFTER_VIDEO_CHANGE_MS = 5000;
    private static final float BADGE_ALPHA_WITHOUT_CONTROLS = 0.55f;

    private static final HoldGestureController instance = new HoldGestureController();

    private WeakReference<HoldGestureOverlayView> overlayRef = new WeakReference<>(null);
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
            interceptor = WindowTouchInterceptor.install(window, this::onWindowTouchEvent);
        }

        final HoldGestureOverlayView previous = overlayRef.get();
        if (previous != null && previous.getParent() instanceof ViewGroup parent) {
            parent.removeView(previous);
        }
        final HoldGestureOverlayView overlay = new HoldGestureOverlayView(overlayRoot.getContext());
        overlayRoot.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayRef = new WeakReference<>(overlay);

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

    private boolean handleTouch(MotionEvent event) {
        final int action = event.getActionMasked();
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
        final HoldGesture.Config config = new HoldGesture.Config(
                Settings.HOLD_GESTURE_SPEED_SWIPE.get(),
                Settings.HOLD_GESTURE_LOCK.get(),
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
        overlay.setSpeedRange(step.speed, minSpeed, maxSpeed);
        overlay.post(this::cancelTouchForApp);

        Logger.printDebug(() -> "Hold begins at " + holdSpeed + "x, speed before: " + speedBeforeHold
                + "x, locked: " + locked);
        gesture = new HoldGesture(config, this);
        gesture.begin(fingerX / density, fingerY / density, room, holdSpeed, locked);
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
    public void onFinished(Outcome outcome, float speed) {
        Logger.printDebug(() -> "Hold ends: " + outcome + " at " + speed + "x");
        gesture = null;
        switch (outcome) {
            case RESTORE -> applySpeed(speedBeforeHold);
            case LOCK -> lock(speed);
            case UNLOCK -> unlock();
        }
        updateBadge();
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
        if (gesture != null && !type.isMaximizedOrFullscreen()) {
            gesture.cancel();
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
