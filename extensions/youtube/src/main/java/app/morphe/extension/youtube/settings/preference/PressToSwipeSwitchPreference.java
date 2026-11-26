/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;
import app.morphe.extension.youtube.settings.Settings;

/**
 * The switch of "press to swipe" of the swipe controls. Turning it on while the hold gesture is on
 * asks which one to keep, as {@link HoldGestureSwitchPreference} does for the other one.
 */
@SuppressWarnings({"deprecation", "unused"})
public class PressToSwipeSwitchPreference extends BooleanSettingPreference {

    public PressToSwipeSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public PressToSwipeSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PressToSwipeSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PressToSwipeSwitchPreference(Context context) {
        super(context);
    }

    /**
     * Also called when the settings are loaded or imported. Only a change by the user differs
     * from the setting, which is saved after this.
     */
    @Override
    public void setChecked(boolean checked) {
        if (!checked || Settings.SWIPE_PRESS_TO_ENGAGE.get() || !Settings.HOLD_GESTURE.get()) {
            super.setChecked(checked);
            return;
        }

        HoldGestureSwitchPreference.askWhichGestureToKeep(getContext(),
                "morphe_hold_gesture_dialog_title", "morphe_hold_gesture_dialog_turn_off",
                () -> {
                    Settings.HOLD_GESTURE.save(false);
                    AbstractPreferenceFragment.setRestartNeeded();
                    super.setChecked(true);
                    notifyChanged();
                }, null, null);
        // Show the value that the setting still has.
        notifyChanged();
    }
}
