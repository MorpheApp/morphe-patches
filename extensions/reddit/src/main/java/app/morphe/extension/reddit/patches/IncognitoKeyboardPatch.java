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
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class IncognitoKeyboardPatch {
    private static boolean initialized;

    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static void modifyEditorInfo(EditorInfo editorInfo) {
        if (editorInfo == null) return;
        if (!Settings.INCOGNITO_KEYBOARD.get()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

        editorInfo.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
    }

    public static void initialize(Application application) {
        if (initialized) return;
        initialized = true;

        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                        IncognitoFocusListener listener = new IncognitoFocusListener(activity);
                        View decorView = activity.getWindow().getDecorView();
                        
                        decorView.getViewTreeObserver().addOnGlobalFocusChangeListener(listener);
                        
                        // Tag the listener onto the decor view so we can retrieve it safely on destroy
                        decorView.setTag(Integer.MAX_VALUE, listener);
                    }

                    @Override
                    public void onActivityDestroyed(@NonNull Activity activity) {
                        View decorView = activity.getWindow().getDecorView();
                        Object listener = decorView.getTag(Integer.MAX_VALUE);
                        if (listener instanceof ViewTreeObserver.OnGlobalFocusChangeListener) {
                            decorView.getViewTreeObserver().removeOnGlobalFocusChangeListener(
                                    (ViewTreeObserver.OnGlobalFocusChangeListener) listener
                            );
                        }
                    }

                    @Override public void onActivityStarted(@NonNull Activity activity) {}
                    @Override public void onActivityResumed(@NonNull Activity activity) {}
                    @Override public void onActivityPaused(@NonNull Activity activity) {}
                    @Override public void onActivityStopped(@NonNull Activity activity) {}
                    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
                }
        );
    }

    private record IncognitoFocusListener(
            WeakReference<Activity> activityRef) implements ViewTreeObserver.OnGlobalFocusChangeListener {

        private IncognitoFocusListener(Activity activity) {
            this(new WeakReference<>(activity));
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            if (newFocus == null || oldFocus == newFocus) return; // Prevent identical re-entry
            if (!(newFocus instanceof TextView)) return;
            if (!Settings.INCOGNITO_KEYBOARD.get()) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;

            Activity activity = activityRef.get();
            if (activity == null) return;

            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm == null) return;

            // Natively triggers onCreateInputConnection -> modifyEditorInfo hooks automatically
            imm.restartInput(newFocus);
        }
    }
}
