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

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"unused", "deprecation"})
public class ResettableEditTextPreference extends EditTextPreference implements SettingPreference {

    /**
     * Setting to show, save and reset.
     */
    @Nullable
    private Setting<?> setting;

    /**
     * If this preference saves the setting, which it leaves to a subclass that turned off persisting.
     */
    private boolean savesSetting;

    public ResettableEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ResettableEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ResettableEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ResettableEditTextPreference(Context context) {
        super(context);
    }

    /**
     * For a preference made in code. Set before it is added to the screen.
     */
    public void setSetting(@Nullable Setting<?> setting) {
        this.setting = setting;
    }

    @Nullable
    public Setting<?> getSetting() {
        return setting;
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        if (setting == null) {
            setting = SettingChange.findSetting(this, Setting.class);
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
    protected boolean persistString(String value) {
        if (!savesSetting) {
            return super.persistString(value);
        }

        SettingChange.save(this, setting, value);
        return true;
    }

    @Override
    public void updateFromSetting() {
        if (setting != null) {
            setText(setting.get().toString());
            setEnabled(setting.isAvailable());
        }
    }

    @Override
    protected void showDialog(Bundle state) {
        try {
            Context context = getContext();
            EditText editText = getEditText();

            // Set initial EditText value to the current persisted value or empty string.
            String initialValue = getText() != null ? getText() : "";
            editText.setText(initialValue);
            editText.setSelection(initialValue.length()); // Move cursor to end.

            // Create custom dialog.
            String neutralButtonText = (setting != null) ? str("morphe_settings_reset") : null;
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    getTitle() != null ? getTitle().toString() : "", // Title.
                    null,     // Message is replaced by EditText.
                    editText, // Pass the EditText.
                    null,     // OK button text.
                    () -> {
                        // OK button action. Persist the EditText value when OK is clicked.
                        String newValue = editText.getText().toString();
                        if (callChangeListener(newValue)) {
                            setText(newValue);
                        }
                    },
                    () -> {}, // Cancel button action (dismiss only).
                    neutralButtonText, // Neutral button text (Reset).
                    () -> {
                        // Neutral button action.
                        if (setting != null) {
                            try {
                                String defaultStringValue = Objects.requireNonNull(setting).defaultValue.toString();
                                editText.setText(defaultStringValue);
                                editText.setSelection(defaultStringValue.length()); // Move cursor to end of text.
                            } catch (Exception ex) {
                                Logger.printException(() -> "reset failure", ex);
                            }
                        }
                    },
                    false // Do not dismiss dialog when onNeutralClick.
            );

            // Show the dialog.
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
