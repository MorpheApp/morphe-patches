/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
import static app.morphe.extension.shared.Utils.hideViewUnderCondition;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

@SuppressWarnings("unused")
public class MiniplayerPreviousNextButtonsPatch {
    private static int previousButtonId = 0;
    private static int nextButtonId = 0;

    /**
     * Injection point.
     */
    public static void setPreviousNextButtonOnClickListener(View view) {
        int previousButtonViewId = getPreviousButtonId();
        if (previousButtonViewId != 0) {
            View previousButtonView = view.findViewById(previousButtonViewId);
            hideViewUnderCondition(!Settings.MINIPLAYER_PREVIOUS_BUTTON.get(), previousButtonView);
            previousButtonView.setOnClickListener(v -> dispatchMediaKeyEvent(v.getContext(), KEYCODE_MEDIA_PREVIOUS));
        }
        int nextButtonViewId = getNextButtonId();
        if (nextButtonViewId != 0) {
            View nextButtonView = view.findViewById(nextButtonViewId);
            hideViewUnderCondition(!Settings.MINIPLAYER_NEXT_BUTTON.get(), nextButtonView);
            nextButtonView.setOnClickListener(v -> dispatchMediaKeyEvent(v.getContext(), KEYCODE_MEDIA_NEXT));
        }
    }

    /**
     * Injection point.
     */
    public static View[] setPreviousNextButton(View view, View[] original) {
        View previousButtonView = null;
        View nextButtonView = null;

        int previousButtonViewId = getPreviousButtonId();
        if (previousButtonViewId != 0) {
            previousButtonView = view.findViewById(previousButtonViewId);
        }
        int nextButtonViewId = getNextButtonId();
        if (nextButtonViewId != 0) {
            nextButtonView = view.findViewById(nextButtonViewId);
        }

        int extraCount = (nextButtonView != null ? 1 : 0) + (previousButtonView != null ? 1 : 0);
        if (extraCount == 0) return original;

        View[] newArray = new View[original.length + extraCount];
        System.arraycopy(original, 0, newArray, 0, original.length);

        int i = original.length;
        if (previousButtonView != null) newArray[i++] = previousButtonView;
        if (nextButtonView != null) newArray[i] = nextButtonView;

        return newArray;
    }

    /**
     * Dispatches a media key event via AudioManager.
     * This is the same mechanism used by Bluetooth headsets and does not require
     * any special permissions. Both ACTION_DOWN and ACTION_UP are sent,
     * as some players ignore events without a matching up event.
     */
    private static void dispatchMediaKeyEvent(Context context, int keyCode) {
        if (context.getSystemService(Context.AUDIO_SERVICE) instanceof AudioManager audioManager) {
            try {
                long now = SystemClock.uptimeMillis();
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, ACTION_DOWN, keyCode, 0));
                audioManager.dispatchMediaKeyEvent(
                        new KeyEvent(now, now, ACTION_UP, keyCode, 0));
            } catch (Exception ex) {
                Logger.printException(() -> "dispatchMediaKeyEvent failure", ex);
            }
        }
    }

    private static int getPreviousButtonId() {
        if (previousButtonId == 0) {
            previousButtonId = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_previous_button");
        }

        return previousButtonId;
    }

    private static int getNextButtonId() {
        if (nextButtonId == 0) {
            nextButtonId = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_next_button");
        }

        return nextButtonId;
    }

}
