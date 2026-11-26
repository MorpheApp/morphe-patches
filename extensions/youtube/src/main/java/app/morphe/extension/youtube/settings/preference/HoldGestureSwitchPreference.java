/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.holdgesture.HoldGestureController;
import app.morphe.extension.youtube.settings.Settings;

/**
 * The switch of the hold gesture. The hold gesture and "press to swipe" of the swipe controls both
 * start with a long press, so only one of them can be on. Turning this one on asks which one to keep,
 * as {@link PressToSwipeSwitchPreference} does for the other one. If both are on anyway, such as
 * after an import, the summary says that the hold gesture is not used, and a tap asks again.
 */
@SuppressWarnings({"deprecation", "unused"})
public class HoldGestureSwitchPreference extends BooleanSettingPreference {

    public HoldGestureSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public HoldGestureSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public HoldGestureSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HoldGestureSwitchPreference(Context context) {
        super(context);
    }

    /**
     * Also called when the settings are loaded or imported. Only a change by the user differs
     * from the setting, which is saved after this.
     */
    @Override
    public void setChecked(boolean checked) {
        if (checked == Settings.HOLD_GESTURE.get() || !HoldGestureController.isPressToSwipeOn()) {
            super.setChecked(checked);
            return;
        }

        final Runnable turnOffPressToSwipe = () -> {
            Settings.SWIPE_PRESS_TO_ENGAGE.save(false);
            AbstractPreferenceFragment.setRestartNeeded();
            super.setChecked(true);
            notifyChanged();
        };
        if (checked) {
            askWhichGestureToKeep(getContext(), "morphe_hold_gesture_press_to_swipe_dialog_title",
                    "morphe_hold_gesture_press_to_swipe_dialog_turn_off", turnOffPressToSwipe, null, null);
        } else {
            // Both are on. Turning off this one, as asked, is one way out, and the other one is offered too.
            askWhichGestureToKeep(getContext(), "morphe_hold_gesture_press_to_swipe_dialog_title",
                    "morphe_hold_gesture_press_to_swipe_dialog_turn_off", turnOffPressToSwipe,
                    "morphe_hold_gesture_dialog_turn_off", () -> super.setChecked(false));
        }
        // Show the value that the setting still has.
        notifyChanged();
    }

    @Override
    public CharSequence getSummary() {
        if (Settings.HOLD_GESTURE.get() && HoldGestureController.isPressToSwipeOn()) {
            return str("morphe_hold_gesture_press_to_swipe_summary");
        }
        return super.getSummary();
    }

    /**
     * Says that the hold gesture and press to swipe cannot both be on, and offers to turn one off.
     * Cancel keeps the settings as they are.
     */
    static void askWhichGestureToKeep(Context context, String titleKey, String turnOffKey, Runnable turnOff,
                                      @Nullable String turnOffOtherKey, @Nullable Runnable turnOffOther) {
        try {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str(titleKey),
                    str("morphe_hold_gesture_press_to_swipe_dialog_message"),
                    null,
                    str(turnOffKey),
                    turnOff,
                    () -> {},
                    turnOffOtherKey == null ? null : str(turnOffOtherKey),
                    turnOffOther,
                    true
            );
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "askWhichGestureToKeep failure", ex);
        }
    }
}
