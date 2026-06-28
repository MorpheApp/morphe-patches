/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.sponsorblock.SponsorBlockSettings;

/**
 * EditTextPreference for SponsorBlock JSON import/export. Loads the current export string on
 * click, lets the user copy or replace it, and routes a confirmed value through the import path.
 * The summary doubles as the warning text when the user has a private user ID set.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockImportExportPreference extends EditTextPreference {

    public SponsorBlockImportExportPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        configure();
    }

    public SponsorBlockImportExportPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        configure();
    }

    public SponsorBlockImportExportPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        configure();
    }

    public SponsorBlockImportExportPreference(Context context) {
        super(context);
        configure();
    }

    private void configure() {
        EditText editText = getEditText();
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setAutofillHints((String) null);
        editText.setTextSize(14);

        refreshSummary();

        setOnPreferenceClickListener(p -> {
            getEditText().setText(SponsorBlockSettings.exportDesktopSettings());
            return true;
        });
        setOnPreferenceChangeListener((p, newValue) -> {
            SponsorBlockSettings.importDesktopSettings((String) newValue);
            refreshSummary();
            return true;
        });
    }

    private void refreshSummary() {
        setSummary(SponsorBlockHelpers.userHasSBPrivateID()
                ? str("morphe_sb_settings_ie_summary_warning")
                : str("morphe_sb_settings_ie_summary"));
    }

    @Override
    protected void showDialog(@Nullable Bundle state) {
        try {
            Context context = getContext();
            EditText editText = getEditText();

            editText.setInputType(editText.getInputType() | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editText.setTextSize(14);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_sb_settings_ie_title"),
                    null,
                    editText,
                    str("morphe_settings_import"),
                    () -> {
                        String newValue = editText.getText().toString();
                        if (getOnPreferenceChangeListener() != null) {
                            getOnPreferenceChangeListener().onPreferenceChange(this, newValue);
                        }
                    },
                    () -> {},
                    str("morphe_sb_settings_copy"),
                    () -> {
                        try {
                            Utils.setClipboard(editText.getText());
                        } catch (Exception ex) {
                            Logger.printException(() -> "Copy import/export failure", ex);
                        }
                    },
                    true
            );

            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
