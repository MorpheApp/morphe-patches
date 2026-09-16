/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2938
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.media.AudioManager;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider.SwipeVolumeSteps;

/**
 * Volume steps list that shows only the step counts the device volume stream can produce.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SwipeVolumeStepsPreference extends CustomDialogListPreference {

    public SwipeVolumeStepsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public SwipeVolumeStepsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SwipeVolumeStepsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SwipeVolumeStepsPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }

        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        if (entries == null || entryValues == null || entries.length != entryValues.length) {
            return;
        }

        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // The preference value is only loaded once it is attached to the hierarchy.
        String currentValue = Settings.SWIPE_VOLUME_STEPS.get().name();
        List<CharSequence> shownEntries = new ArrayList<>(entries.length);
        List<CharSequence> shownValues = new ArrayList<>(entryValues.length);

        for (int i = 0, length = entryValues.length; i < length; i++) {
            String value = entryValues[i].toString();
            int steps = stepsForValue(value);

            // A step of a single volume index is what the device already does,
            // so such an entry would silently change nothing if it was shown.
            if (steps > 0 && SwipeControlsConfigurationProvider.volumeStepSize(maxVolume, steps) < 2
                    && !value.equals(currentValue)) {
                continue;
            }

            // Show how many levels the device itself has, since every other entry is compared to it.
            shownEntries.add(steps == 0 ? entries[i] + " (" + maxVolume + ")" : entries[i]);
            shownValues.add(value);
        }

        setEntries(shownEntries.toArray(new CharSequence[0]));
        setEntryValues(shownValues.toArray(new CharSequence[0]));
    }

    /**
     * @return The number of steps of an entry value, 0 for the device default, or -1 if unknown.
     */
    private static int stepsForValue(String value) {
        try {
            return SwipeVolumeSteps.valueOf(value).getSteps();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }
}
