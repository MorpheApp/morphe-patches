/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;

/**
 * Opens the SponsorBlock submission guidelines wiki page when tapped.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockGuidelinesPreference extends Preference {

    private static final Uri GUIDELINES_URI = Uri.parse("https://wiki.sponsor.ajay.app/w/Guidelines");

    public SponsorBlockGuidelinesPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public SponsorBlockGuidelinesPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public SponsorBlockGuidelinesPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public SponsorBlockGuidelinesPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(p -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, GUIDELINES_URI);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception ex) {
                Logger.printException(() -> "Open guidelines failure", ex);
            }
            return true;
        });
    }
}
