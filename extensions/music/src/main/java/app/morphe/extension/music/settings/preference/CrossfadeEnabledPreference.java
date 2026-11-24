/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import app.morphe.extension.shared.settings.preference.BooleanSettingPreference;

/**
 * The crossfade switch, which says what Spoof video streams must be set to while crossfade is unavailable.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CrossfadeEnabledPreference extends BooleanSettingPreference {

    public CrossfadeEnabledPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public CrossfadeEnabledPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public CrossfadeEnabledPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public CrossfadeEnabledPreference(Context context) {
        super(context);
    }

    /**
     * The settings screen enables the preference by the availability of the setting,
     * also when the Spoof video streams settings change.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setSummary(enabled ? null : str("morphe_music_crossfade_enabled_summary_unavailable"));
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        // The disabled grey is hard to read on the dark theme, and the summary says how to enable crossfade.
        View summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setEnabled(true);
        }
    }
}
