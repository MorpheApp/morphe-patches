/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.patches.subscriptiongroups.SubscriptionGroupsDialog;

/**
 * Opens the subscription channel groups management dialog.
 * Mirrors ChannelWhitelistPreference: constructors plus a click
 * listener that opens the management dialog.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SubscriptionGroupsPreference extends Preference {

    public SubscriptionGroupsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public SubscriptionGroupsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public SubscriptionGroupsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public SubscriptionGroupsPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(preference -> {
            SubscriptionGroupsDialog.show(preference.getContext());
            return true;
        });
    }
}
