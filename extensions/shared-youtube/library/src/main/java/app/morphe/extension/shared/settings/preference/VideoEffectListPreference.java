/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.patches.VideoEffectsPatch;
import app.morphe.extension.shared.patches.VideoEffectsPatch.CustomEffect;
import app.morphe.extension.shared.patches.VideoEffectsPatch.VideoEffect;
import app.morphe.extension.shared.settings.Setting;

/**
 * The video effects, with the custom effects after them.
 */
@SuppressWarnings({"unused", "deprecation"})
public class VideoEffectListPreference extends CustomDialogListPreference {

    private CharSequence[] effectEntries;
    private CharSequence[] effectEntryValues;

    public VideoEffectListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public VideoEffectListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public VideoEffectListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VideoEffectListPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Entries of the effects, from the patch resources.
        effectEntries = getEntries();
        effectEntryValues = getEntryValues();
    }

    /**
     * Adds the custom effects to the entries.
     */
    private void updateEntries() {
        List<CustomEffect> customEffects = VideoEffectsPatch.getCustomEffects();
        final int effectCount = effectEntries.length;
        CharSequence[] entries = Arrays.copyOf(effectEntries, effectCount + customEffects.size());
        CharSequence[] entryValues = Arrays.copyOf(effectEntryValues, entries.length);
        for (int i = 0, size = customEffects.size(); i < size; i++) {
            String name = customEffects.get(i).name;
            entries[effectCount + i] = name;
            entryValues[effectCount + i] = VideoEffectsPatch.CUSTOM_EFFECT_NAME_PREFIX + name;
        }
        setEntries(entries);
        setEntryValues(entryValues);
    }

    @Override
    public void updateFromSetting() {
        Setting<?> setting = getSetting();
        if (setting == null) {
            return;
        }

        updateEntries();
        String value = VideoEffectsPatch.getSelectedEffectEntryValue();
        setValue(value);
        final int entryIndex = findIndexOfValue(value);
        // The selected custom effect can be missing after an import.
        setSummary(entryIndex >= 0 ? getEntries()[entryIndex] : value);
        setEnabled(setting.isAvailable());
    }

    @Override
    protected boolean persistString(String value) {
        if (getSetting() == null) {
            return super.persistString(value);
        }

        VideoEffect effect = VideoEffectsPatch.saveCustomEffectNameOfEntryValue(value);
        return super.persistString(effect.name());
    }

    @Override
    protected void showDialog(Bundle state) {
        // The custom effects may have changed.
        updateEntries();
        super.showDialog(state);
    }

    @Override
    public boolean tracksNewEntries() {
        return false; // Custom effects are user data.
    }
}
