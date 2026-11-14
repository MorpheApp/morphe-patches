/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * A switch of a {@link BooleanSetting}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class BooleanSettingPreference extends SwitchPreference implements SettingPreference {

    @Nullable
    private BooleanSetting setting;

    /**
     * If this preference saves the setting, which it leaves to a subclass that turned off persisting.
     */
    private boolean savesSetting;

    public BooleanSettingPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public BooleanSettingPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public BooleanSettingPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public BooleanSettingPreference(Context context) {
        super(context);
    }

    /**
     * For a preference made in code.
     */
    public BooleanSettingPreference(Context context, BooleanSetting setting) {
        super(context);
        setKey(setting.key);
        this.setting = setting;
    }

    @Nullable
    public BooleanSetting getSetting() {
        return setting;
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        if (setting == null) {
            setting = SettingChange.findSetting(this, BooleanSetting.class);
        }
        savesSetting = setting != null && isPersistent();
        if (savesSetting) {
            // Before attaching, which would read the value from the shared preferences.
            setPersistent(false);
        }

        super.onAttachedToHierarchy(preferenceManager);
        updateFromSetting();
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        if (!savesSetting) {
            return super.persistBoolean(value);
        }

        SettingChange.save(this, setting, String.valueOf(value));
        return true;
    }

    @Override
    public void updateFromSetting() {
        if (setting != null) {
            setChecked(setting.get());
            setEnabled(setting.isAvailable());
        }
    }
}
