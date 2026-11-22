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

import app.morphe.extension.shared.patches.VideoEffectsPatch;

/**
 * Off and the names of the video effects.
 */
@SuppressWarnings({"unused", "deprecation"})
public class VideoEffectListPreference extends CustomDialogListPreference {

    public VideoEffectListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateEntries();
    }

    public VideoEffectListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateEntries();
    }

    public VideoEffectListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateEntries();
    }

    public VideoEffectListPreference(Context context) {
        super(context);
        updateEntries();
    }

    private void updateEntries() {
        setEntries(VideoEffectsPatch.getEffectEntries());
        setEntryValues(VideoEffectsPatch.getEffectEntryValues());
    }

    @Override
    public void updateFromSetting() {
        // The effects may have changed.
        updateEntries();
        super.updateFromSetting();
    }

    @Override
    protected void showDialog(Bundle state) {
        updateEntries();
        super.showDialog(state);
    }

    @Override
    public boolean tracksNewEntries() {
        return false; // The effects are user data.
    }
}
