/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.preference.Preference;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Finds the settings of {@link SettingPreference} instances and saves what the user picks in them.
 */
@SuppressWarnings("deprecation")
final class SettingChange {

    private SettingChange() {
    }

    /**
     * @return The setting of the key of the preference, or null if it has none of the type.
     */
    @Nullable
    static <S extends Setting<?>> S findSetting(Preference preference, Class<S> type) {
        String key = preference.getKey();
        if (key == null) {
            // Set later, such as by a preference made in code.
            return null;
        }

        Setting<?> setting = Setting.getSettingFromPath(key);
        if (type.isInstance(setting)) {
            return type.cast(setting);
        }

        // Probably a typo in the patches preference declaration.
        Logger.printExceptionIfDebug(() -> "Preference key has no " + type.getSimpleName() + ": " + key);
        return null;
    }

    /**
     * Saves a value the user picked. If the setting asks the user to confirm a change, it is saved
     * once the user confirms it, and the preference shows the value of the setting again if not.
     *
     * @param newValue The value as a string, as {@link Setting#saveFromString(String)} reads it.
     */
    static <P extends Preference & SettingPreference> void save(P preference, Setting<?> setting, String newValue) {
        if (newValue.equals(setting.get().toString())) {
            // Shown from the setting.
            return;
        }

        if (setting.userDialogMessage != null && !newValue.equals(setting.defaultValue.toString())) {
            showConfirmation(preference, setting, newValue);
            return;
        }

        saveConfirmed(preference, setting, newValue);
    }

    private static <P extends Preference & SettingPreference> void saveConfirmed(
            P preference, Setting<?> setting, String newValue) {
        try {
            setting.saveFromString(newValue);
            if (setting.rebootApp) {
                AbstractPreferenceFragment.setRestartNeeded();
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not save " + setting.key + ": " + newValue, ex);
            // Show the value the setting kept.
            preference.updateFromSetting();
        }
    }

    private static <P extends Preference & SettingPreference> void showConfirmation(
            P preference, Setting<?> setting, String newValue) {
        Utils.verifyOnMainThread();

        if (AbstractPreferenceFragment.confirmDialogTitle == null) {
            AbstractPreferenceFragment.confirmDialogTitle = str("morphe_settings_confirm_user_dialog_title");
        }

        CharSequence message = BulletPointPreference.formatIntoBulletPoints(
                Objects.requireNonNull(setting.userDialogMessage).toString());

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                preference.getContext(),
                AbstractPreferenceFragment.confirmDialogTitle, // Title.
                message,
                null, // No EditText.
                null, // OK button text.
                () -> saveConfirmed(preference, setting, newValue),
                // Cancel button action. Show the value the setting still has.
                preference::updateFromSetting,
                null, // No Neutral button.
                null, // No Neutral button action.
                true  // Dismiss dialog when onNeutralClick.
        );

        dialogPair.first.setCancelable(false);
        dialogPair.first.show();
    }
}
