/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.whitelist.ChannelWhitelistDialog;
import app.morphe.extension.youtube.whitelist.WhitelistType;

/**
 * Opens the channel whitelist management dialog of the type matching this preference key.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ChannelWhitelistPreference extends Preference {

    public ChannelWhitelistPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public ChannelWhitelistPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public ChannelWhitelistPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public ChannelWhitelistPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(preference -> {
            WhitelistType type = WhitelistType.fromPreferenceKey(preference.getKey());
            if (type == null) {
                Logger.printException(() -> "Unknown whitelist preference key: " + preference.getKey());
                return false;
            }
            ChannelWhitelistDialog.show(preference.getContext(), type);
            return true;
        });
    }
}
