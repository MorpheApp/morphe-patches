/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4909
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.patches.spoof.SpoofDeviceDimensionsPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Note in the download settings to turn on Spoof device dimensions.
 * It is enabled only if in-app downloads are on, and this device has a hardware video decoder
 * below 1080p that Spoof device dimensions can unlock.
 */
@SuppressWarnings({"deprecation", "unused"})
public class DownloadQualityAboutPreference extends Preference {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, str) -> {
        // This listener may run before the settings fragment updates Settings,
        // so update after all other listeners are done.
        Utils.runOnMainThread(this::updateUI);
    };

    public DownloadQualityAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DownloadQualityAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DownloadQualityAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DownloadQualityAboutPreference(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateUI();
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void updateUI() {
        try {
            setEnabled(Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP
                    && !Settings.SPOOF_DEVICE_DIMENSIONS.get()
                    && SpoofDeviceDimensionsPatch.isVideoDecoderSizeLimited());
        } catch (Exception ex) {
            Logger.printException(() -> "updateUI failure", ex);
        }
    }
}
