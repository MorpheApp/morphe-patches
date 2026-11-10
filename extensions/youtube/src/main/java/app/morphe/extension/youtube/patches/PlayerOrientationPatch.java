/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class PlayerOrientationPatch {

    public enum OrientationMode {
        FREE,
        SYSTEM,
        DEFAULT
    }

    private static volatile boolean isOrientationLocked = false;
    private static WeakReference<View> lastAttachedButton = new WeakReference<>(null);

    // Must be a strong reference, SharedPreferences holds listeners weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCE_LISTENER =
            (prefs, key) -> {
                if ("morphe_player_orientation".equals(key)) {
                    isOrientationLocked = false;
                    Activity activity = Utils.getActivity();
                    if (activity != null) {
                        applyOrientation(activity, PlayerType.getCurrent());
                    }
                }
            };
    private static boolean preferenceListenerRegistered = false;

    private static void ensureListenerRegistered() {
        try {
            if (!preferenceListenerRegistered && Setting.preferences != null && Setting.preferences.preferences != null) {
                Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(PREFERENCE_LISTENER);
                preferenceListenerRegistered = true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Error registering orientation preference listener", ex);
        }
    }

    private PlayerOrientationPatch() {
    }

    /**
     * Called when the fullscreen button is bound.
     */
    public static void initializeButton(@Nullable View fullscreenButton) {
        ensureListenerRegistered();
        if (fullscreenButton == null) return;
        if (lastAttachedButton.get() == fullscreenButton) return;
        lastAttachedButton = new WeakReference<>(fullscreenButton);

        fullscreenButton.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                try {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            downTime = System.currentTimeMillis();
                            break;
                        case MotionEvent.ACTION_UP:
                            long elapsed = System.currentTimeMillis() - downTime;
                            float dx = Math.abs(event.getX() - downX);
                            float dy = Math.abs(event.getY() - downY);
                            if (elapsed < 500 && dx < 50 && dy < 50) {
                                if (onFullscreenButtonClicked()) {
                                    v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                                    return true;
                                }
                            }
                            break;
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Error in fullscreen button touch listener", ex);
                }
                return false;
            }
        });
    }

    /**
     * Intercepts the click on the fullscreen button.
     * Returns true if consumed (e.g. orientation locked/unlocked), false otherwise.
     */
    public static boolean onFullscreenButtonClicked() {
        try {
            if (Settings.ORIENTATION.get() != OrientationMode.FREE) {
                return false;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) return false;

            int currentOrientation = activity.getResources().getConfiguration().orientation;

            // In portrait and not locked: let YouTube enter fullscreen normally
            if (!isOrientationLocked && currentOrientation != Configuration.ORIENTATION_LANDSCAPE) {
                return false;
            }

            // In landscape or already locked: toggle orientation lock
            if (!isOrientationLocked) {
                isOrientationLocked = true;
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                showToast(ResourceUtils.getString("morphe_player_orientation_toast_locked"));
            } else {
                isOrientationLocked = false;
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                showToast(ResourceUtils.getString("morphe_player_orientation_toast_unlocked"));
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onFullscreenButtonClicked failure", ex);
            return false;
        }
    }

    /**
     * Injection point for player state changes.
     */
    public static void onPlayerTypeChanged(@Nullable Enum<?> playerType) {
        try {
            ensureListenerRegistered();
            if (playerType == null) return;

            String name = playerType.name();
            if ("WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN".equals(name)
                    || "WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED".equals(name)) {
                return;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) return;

            PlayerType type = PlayerType.getCurrent();
            applyOrientation(activity, type);
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerTypeChanged failure", ex);
        }
    }

    private static void applyOrientation(Activity activity, @Nullable PlayerType playerType) {
        OrientationMode mode = Settings.ORIENTATION.get();
        boolean isPlaying = playerType != null && (playerType == PlayerType.WATCH_WHILE_MAXIMIZED
                || playerType == PlayerType.WATCH_WHILE_FULLSCREEN);

        if (mode == OrientationMode.FREE) {
            if (isPlaying) {
                if (!isOrientationLocked) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                }
            } else {
                isOrientationLocked = false;
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        } else if (mode == OrientationMode.SYSTEM) {
            if (isPlaying) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
            } else {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        } else {
            // DEFAULT
            if (!isPlaying) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        }
    }

    private static void showToast(String message) {
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getActivity();
            if (activity != null) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
