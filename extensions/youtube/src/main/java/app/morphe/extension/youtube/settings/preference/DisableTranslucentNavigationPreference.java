/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2911
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.patches.NavigationBarPatch;

@SuppressWarnings({"deprecation", "unused"})
public class DisableTranslucentNavigationPreference extends SwitchPreference {

    private static final boolean AVAILABLE = NavigationBarPatch.
            DisableTranslucentNavigationAvailability.canDisableTranslucentNavigationBar();

    {
        if (!AVAILABLE) {
            setSummary(str("morphe_disable_translucent_navigation_summary_not_available"));
            super.setEnabled(false);
        }
    }

    public DisableTranslucentNavigationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public DisableTranslucentNavigationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public DisableTranslucentNavigationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public DisableTranslucentNavigationPreference(Context context) {
        super(context);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (AVAILABLE) {
            super.setEnabled(enabled);
        }
    }
}
