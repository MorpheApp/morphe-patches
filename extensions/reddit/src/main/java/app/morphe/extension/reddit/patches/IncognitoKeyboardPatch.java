/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1917
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
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
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class IncognitoKeyboardPatch {
    private static boolean initialized;

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
                    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                        activity.getWindow().getDecorView().getViewTreeObserver()
                                .addOnGlobalFocusChangeListener(new IncognitoFocusListener(activity));
                    }

                    @Override public void onActivityStarted(@NonNull Activity activity) {}
                    @Override public void onActivityResumed(@NonNull Activity activity) {}
                    @Override public void onActivityPaused(@NonNull Activity activity) {}
                    @Override public void onActivityStopped(@NonNull Activity activity) {}
                    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
                    @Override public void onActivityDestroyed(@NonNull Activity activity) {}
                }
        );
    }

    private record IncognitoFocusListener(
            WeakReference<Activity> activityRef) implements ViewTreeObserver.OnGlobalFocusChangeListener {

        private IncognitoFocusListener(Activity activityRef) {
            this(new WeakReference<>(activityRef));
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            if (newFocus == null) return;
            if (!(newFocus instanceof TextView)) return;
            if (!Settings.INCOGNITO_KEYBOARD.get()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

            restartInputWithIncognito((TextView) newFocus);
        }

        private void restartInputWithIncognito(TextView textView) {
            Activity activity = activityRef.get();
            if (activity == null) return;

            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm == null) return;

            EditorInfo editorInfo = new EditorInfo();
            InputConnection ic;
            try {
                ic = textView.onCreateInputConnection(editorInfo);
            } catch (Exception ex) {
                Logger.printException(() -> "onCreateInputConnection failed", ex);
                return;
            }
            if (ic == null) return;

            editorInfo.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

            try {
                Method startInputAsync = InputMethodManager.class.getMethod(
                        "startInputAsync", View.class, EditorInfo.class
                );
                startInputAsync.invoke(imm, textView, editorInfo);
            } catch (NoSuchMethodException | IllegalAccessException |
                     InvocationTargetException ex) {
                Logger.printException(() -> "startInputAsync reflection failed", ex);
            }
        }
    }
}
