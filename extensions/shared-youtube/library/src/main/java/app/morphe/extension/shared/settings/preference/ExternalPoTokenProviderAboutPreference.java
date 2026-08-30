/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

@SuppressWarnings({"deprecation", "unused"})
public class ExternalPoTokenProviderAboutPreference extends Preference {

    /**
     * Callback when the app is resumed. Used to enable preference switch after helper is installed.
     */
    private final Application.ActivityLifecycleCallbacks ACTIVITY_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {

        public void onActivityResumed(@NonNull Activity activity) {
            Logger.printDebug(() -> "onActivityResumed");
            updateUI();
        }

        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        public void onActivityStarted(@NonNull Activity a) {}
        public void onActivityPaused(@NonNull Activity a) {}
        public void onActivityStopped(@NonNull Activity a) {}
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        public void onActivityDestroyed(@NonNull Activity a) {}
    };

    private static boolean isAvailable() {
        return SharedYouTubeSettings.EXTERNAL_POTOKEN_PROVIDER.isAvailable();
    }

    private void registerApplicationOnResumeCallback() {
        SpoofVideoStreamsPatch.getApplication().registerActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    private void unregisterApplicationOnResumeCallback() {
        SpoofVideoStreamsPatch.getApplication().unregisterActivityLifecycleCallbacks(
                ACTIVITY_LIFECYCLE_CALLBACKS
        );
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        registerApplicationOnResumeCallback();
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        unregisterApplicationOnResumeCallback();
    }

    private void updateUI() {
        String summaryKey = isAvailable()
                ? "morphe_external_potoken_provider_settings_summary"
                : "morphe_external_potoken_provider_about_summary";
        setSummary(str(summaryKey));
    }

    @Override
    protected void onClick() {
        Intent intent;
        if (isAvailable()) {
            intent = new Intent();
            intent.setClassName("app.morphe.pot.helper", "app.morphe.pot.helper.MainActivity");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/MorpheApp/PotHelper/releases/latest"));
        }
        getContext().startActivity(intent);
    }

    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ExternalPoTokenProviderAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ExternalPoTokenProviderAboutPreference(Context context) {
        super(context);
    }
}

