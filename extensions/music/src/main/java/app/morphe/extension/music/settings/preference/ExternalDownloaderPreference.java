/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.preference.SharedExternalDownloaderPreference;

@SuppressWarnings("unused")
public class ExternalDownloaderPreference extends SharedExternalDownloaderPreference {

    public ExternalDownloaderPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ExternalDownloaderPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ExternalDownloaderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ExternalDownloaderPreference(Context context) {
        super(context);
    }

    @Override
    protected String getCurrentPackageName() {
        return Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.get();
    }

    @Override
    protected String getDefaultPackageName() {
        return Settings.EXTERNAL_DOWNLOADER_PACKAGE_NAME.defaultValue;
    }
}
