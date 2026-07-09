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
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Opens an EditText dialog for the SponsorBlock API URL with validate-on-OK and a Reset action.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockApiUrlPreference extends Preference {

    public SponsorBlockApiUrlPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public SponsorBlockApiUrlPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public SponsorBlockApiUrlPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public SponsorBlockApiUrlPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(p -> {
            Context context = p.getContext();
            EditText editText = new EditText(context);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            editText.setText(Settings.SB_API_URL.get());

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_sb_api_url_title"),
                    null,
                    editText,
                    null,
                    () -> {
                        String serverAddress = editText.getText().toString();
                        if (!SponsorBlockHelpers.isValidSBServerAddress(serverAddress)) {
                            Utils.showToastLong(str("morphe_sb_api_url_invalid"));
                        } else if (!serverAddress.equals(Settings.SB_API_URL.get())) {
                            Settings.SB_API_URL.save(serverAddress);
                            Utils.showToastLong(str("morphe_sb_api_url_changed"));
                        }
                    },
                    () -> {},
                    str("morphe_settings_reset"),
                    () -> {
                        Settings.SB_API_URL.resetToDefault();
                        Utils.showToastLong(str("morphe_sb_api_url_reset"));
                    },
                    true
            );

            dialogPair.first.show();
            return true;
        });
    }
}
