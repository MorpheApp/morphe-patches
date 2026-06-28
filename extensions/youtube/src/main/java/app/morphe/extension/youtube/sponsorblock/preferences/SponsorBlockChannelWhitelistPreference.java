/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock.preferences;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.sponsorblock.SponsorBlockUtils;

/**
 * Opens the channel whitelist management dialog when tapped.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SponsorBlockChannelWhitelistPreference extends Preference {

    public SponsorBlockChannelWhitelistPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public SponsorBlockChannelWhitelistPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public SponsorBlockChannelWhitelistPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public SponsorBlockChannelWhitelistPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(p -> {
            SponsorBlockUtils.showChannelWhitelistDialog(p.getContext());
            return true;
        });
    }
}
