/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.youtube.sponsorblock.preferences.SponsorBlockPrivateUserIdPreference;

/**
 * The SponsorBlock private user id, which DeArrow also uses.
 * Uses its own strings since the SponsorBlock strings are only added with the SponsorBlock patch.
 */
@SuppressWarnings({"unused", "deprecation"})
public class DeArrowPrivateUserIdPreference extends SponsorBlockPrivateUserIdPreference {

    public DeArrowPrivateUserIdPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DeArrowPrivateUserIdPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DeArrowPrivateUserIdPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DeArrowPrivateUserIdPreference(Context context) {
        super(context);
    }

    @Override
    protected String getInvalidUserIdMessage() {
        return str("morphe_dearrow_private_user_id_invalid");
    }

    @Override
    protected String getCopyButtonText() {
        return str("morphe_settings_import_copy");
    }
}
