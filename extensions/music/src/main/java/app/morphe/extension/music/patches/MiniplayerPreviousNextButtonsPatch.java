/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.Utils.hideViewUnderCondition;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class MiniplayerPreviousNextButtonsPatch {

    private static WeakReference<View> nextButtonViewRef = new WeakReference<>(null);
    private static WeakReference<View> previousButtonViewRef = new WeakReference<>(null);

    // Called from MppWatchWhileLayout.onFinishInflate to store button view references.
    public static void setNextButtonView(View view) {
        nextButtonViewRef = new WeakReference<>(view);
    }

    public static void setPreviousButtonView(View view) {
        previousButtonViewRef = new WeakReference<>(view);
    }

    // Called from the miniplayer constructor to register click listeners.
    public static void setNextButtonOnClickListener(View view) {
        if (view == null) return;
        hideViewUnderCondition(!Settings.MINIPLAYER_NEXT_BUTTON.get(), view);
        view.setOnClickListener(v -> nextButtonClicked());
    }

    public static void setPreviousButtonOnClickListener(View view) {
        if (view == null) return;
        hideViewUnderCondition(!Settings.MINIPLAYER_PREVIOUS_BUTTON.get(), view);
        view.setOnClickListener(v -> previousButtonClicked());
    }

    /**
     * Appends the next/previous button views to the existing view array
     * so the miniplayer layout includes them in its managed view set.
     */
    public static View[] getViewArray(View[] original) {
        View nextButton = nextButtonViewRef.get();
        View previousButton = previousButtonViewRef.get();

        int extraCount = (nextButton != null ? 1 : 0) + (previousButton != null ? 1 : 0);
        if (extraCount == 0) return original;

        View[] extended = new View[original.length + extraCount];
        System.arraycopy(original, 0, extended, 0, original.length);

        int i = original.length;
        if (previousButton != null) extended[i++] = previousButton;
        if (nextButton != null) extended[i] = nextButton;

        return extended;
    }

    public static void nextButtonClicked() {
        dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void previousButtonClicked() {
        dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /**
     * Dispatches a media key event via AudioManager.
     * This is the same mechanism used by Bluetooth headsets and does not require
     * any special permissions. Both ACTION_DOWN and ACTION_UP are sent,
     * as some players ignore events without a matching up event.
     */
    private static void dispatchMediaKeyEvent(int keyCode) {
        try {
            Context context = Utils.getContext();
            if (context == null) return;

            AudioManager audioManager = (AudioManager)
                    context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;

            long now = SystemClock.uptimeMillis();
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(
                    new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ex) {
            Logger.printException(() -> "dispatchMediaAction failure", ex);
        }
    }
}
