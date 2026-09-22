/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3174
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

@SuppressWarnings({"unused", "deprecation"})
public class LyricsTranslationLanguagePreference extends CustomDialogListPreference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public LyricsTranslationLanguagePreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public LyricsTranslationLanguagePreference(Context context, AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LyricsTranslationLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LyricsTranslationLanguagePreference(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        cachedParent = getParent();
        try {
            SharedPreferences prefs = preferenceManager.getSharedPreferences();
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(this);
                updateVisibility();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "onAttachedToHierarchy failure", ex);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (Settings.LYRICS_SHOW_TRANSLATE_BUTTON.key.equals(key)) {
            Utils.runOnMainThread(this::updateVisibility);
        }
    }

    private boolean isCurrentlyVisible = true;
    private PreferenceGroup cachedParent;

    private void updateVisibility() {
        boolean shouldBeVisible = Settings.LYRICS_SHOW_TRANSLATE_BUTTON.get();
        if (shouldBeVisible == isCurrentlyVisible) return;
        try {
            if (cachedParent == null) {
                cachedParent = getParent();
            }
            if (cachedParent == null) return;
            if (shouldBeVisible) {
                cachedParent.addPreference(this);
                isCurrentlyVisible = true;
            } else {
                cachedParent.removePreference(this);
                isCurrentlyVisible = false;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "updateVisibility failure", ex);
        }
    }
}
