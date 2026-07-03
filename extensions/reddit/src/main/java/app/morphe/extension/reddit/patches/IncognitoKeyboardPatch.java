/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class IncognitoKeyboardPatch {
    private static final String TAG = "IncognitoKeyboard";
    private static boolean initialized = false;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point for direct EditorInfo modification in onCreateInputConnection.
     * Called from injected bytecode - adds the incognito flag to the EditorInfo.
     *
     * @param editorInfo The EditorInfo to modify (may be null).
     */
    public static void modifyEditorInfo(EditorInfo editorInfo) {
        if (editorInfo == null) return;
        if (!Settings.INCOGNITO_KEYBOARD.get()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

        editorInfo.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
    }

    /**
     * Initialize the keyboard lifecycle hooks.
     * Called from application onCreate via injected bytecode.
     */
    public static void initialize(Application application) {
        if (initialized) return;
        initialized = true;

        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        activity.getWindow().getDecorView().getViewTreeObserver()
                                .addOnGlobalFocusChangeListener(new IncognitoFocusListener(activity));
                    }

                    @Override public void onActivityStarted(Activity activity) {}
                    @Override public void onActivityResumed(Activity activity) {}
                    @Override public void onActivityPaused(Activity activity) {}
                    @Override public void onActivityStopped(Activity activity) {}
                    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                    @Override public void onActivityDestroyed(Activity activity) {}
                }
        );
    }

    private static class IncognitoFocusListener
            implements ViewTreeObserver.OnGlobalFocusChangeListener {
        private final WeakReference<Activity> activityRef;

        IncognitoFocusListener(Activity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            if (newFocus == null) return;
            if (!(newFocus instanceof android.widget.TextView)) return;
            if (!Settings.INCOGNITO_KEYBOARD.get()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

            restartInputWithIncognito((android.widget.TextView) newFocus);
        }

        private void restartInputWithIncognito(android.widget.TextView textView) {
            Activity activity = activityRef.get();
            if (activity == null) return;

            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm == null) return;

            EditorInfo editorInfo = new EditorInfo();
            InputConnection ic;
            try {
                ic = textView.onCreateInputConnection(editorInfo);
            } catch (Exception e) {
                Logger.printException(() -> TAG + ": onCreateInputConnection failed", e);
                return;
            }
            if (ic == null) return;

            editorInfo.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    imm.startInputAsync(ic, editorInfo);
                } else {
                    restartInputDeprecated(imm, textView, ic, editorInfo);
                }
            } catch (Exception e) {
                Logger.printException(() -> TAG + ": restartInput failed", e);
            }
        }

        @SuppressWarnings("JavaReflectionMemberAccess")
        private void restartInputDeprecated(
                InputMethodManager imm, View view,
                InputConnection ic, EditorInfo editorInfo
        ) {
            try {
                Method startInput = InputMethodManager.class.getMethod(
                        "startInput", View.class, InputConnection.class, EditorInfo.class
                );
                startInput.invoke(imm, view, ic, editorInfo);
            } catch (NoSuchMethodException | IllegalAccessException
                     | InvocationTargetException e) {
                Logger.printException(() -> TAG + ": startInput reflection failed", e);
            }
        }
    }
}
