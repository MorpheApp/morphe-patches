/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Opens the DeArrow title guidelines when tapped.
 */
@SuppressWarnings({"unused", "deprecation"})
public class DeArrowGuidelinesPreference extends URLLinkPreference {

    static final String GUIDELINES_URL = "https://wiki.sponsor.ajay.app/w/DeArrow/Guidelines";

    {
        externalURL = GUIDELINES_URL;
    }

    public DeArrowGuidelinesPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DeArrowGuidelinesPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DeArrowGuidelinesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DeArrowGuidelinesPreference(Context context) {
        super(context);
    }
}
