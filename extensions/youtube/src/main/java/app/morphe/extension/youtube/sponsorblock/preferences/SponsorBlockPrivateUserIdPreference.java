/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * EditTextPreference whose dialog adds a Copy action and validates the user ID before saving.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockPrivateUserIdPreference extends EditTextPreference {

    public SponsorBlockPrivateUserIdPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installChangeListener();
    }

    public SponsorBlockPrivateUserIdPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installChangeListener();
    }

    public SponsorBlockPrivateUserIdPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installChangeListener();
    }

    public SponsorBlockPrivateUserIdPreference(Context context) {
        super(context);
        installChangeListener();
    }

    private void installChangeListener() {
        setOnPreferenceChangeListener((p, newValue) -> {
            String newUUID = newValue.toString();
            if (!SponsorBlockHelpers.isValidSBUserID(newUUID)) {
                Utils.showToastLong(str("morphe_sb_private_user_id_Do_Not_Share_invalid"));
                return false;
            }
            return true;
        });
    }

    @Override
    protected void showDialog(@Nullable Bundle state) {
        try {
            Context context = getContext();
            EditText editText = getEditText();

            String initialValue = getText() != null ? getText() : "";
            editText.setText(initialValue);
            editText.setSelection(initialValue.length());

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    getTitle() != null ? getTitle().toString() : "",
                    null,
                    editText,
                    null,
                    () -> {
                        String newValue = editText.getText().toString();
                        if (callChangeListener(newValue)) {
                            setText(newValue);
                        }
                    },
                    () -> {},
                    str("morphe_sb_settings_copy"),
                    () -> {
                        try {
                            Utils.setClipboard(getEditText().getText());
                        } catch (Exception ex) {
                            Logger.printException(() -> "Copy private user ID failure", ex);
                        }
                    },
                    true
            );

            dialogPair.first.setCancelable(true);
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
