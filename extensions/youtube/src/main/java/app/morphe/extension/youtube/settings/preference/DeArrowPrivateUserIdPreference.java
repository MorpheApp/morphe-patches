/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.youtube.sponsorblock.preferences.SponsorBlockPrivateUserIdPreference;

/**
 * The SponsorBlock private user id, which DeArrow also uses.
 */
@SuppressWarnings("unused")
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
}
