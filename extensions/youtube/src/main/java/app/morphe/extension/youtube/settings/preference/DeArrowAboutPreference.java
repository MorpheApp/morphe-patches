/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the DeArrow about preference to open the DeArrow website.
 */
@SuppressWarnings("unused")
public class DeArrowAboutPreference extends URLLinkPreference {
    {
        externalURL = "https://dearrow.ajay.app";
    }

    public DeArrowAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public DeArrowAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public DeArrowAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public DeArrowAboutPreference(Context context) {
        super(context);
    }
}
