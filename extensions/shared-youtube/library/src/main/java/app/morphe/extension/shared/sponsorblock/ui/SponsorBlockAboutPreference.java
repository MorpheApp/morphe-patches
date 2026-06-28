/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock.ui;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

@SuppressWarnings("unused")
public class SponsorBlockAboutPreference extends URLLinkPreference {
    {
        externalURL = "https://sponsor.ajay.app";
    }

    public SponsorBlockAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public SponsorBlockAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public SponsorBlockAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public SponsorBlockAboutPreference(Context context) {
        super(context);
    }
}
