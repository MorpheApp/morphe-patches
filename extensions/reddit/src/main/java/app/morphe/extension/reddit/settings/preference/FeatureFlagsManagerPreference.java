/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5201
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import app.morphe.extension.reddit.patches.EnableDebuggingPatch;
import app.morphe.extension.shared.settings.preference.AbstractFeatureFlagsManagerPreference;
import app.morphe.extension.shared.settings.preference.FeatureFlags;

/**
 * Feature flags manager of Reddit, where the flags are experiment names.
 */
@SuppressWarnings("deprecation")
public class FeatureFlagsManagerPreference extends AbstractFeatureFlagsManagerPreference<String> {

    public FeatureFlagsManagerPreference(Context context) {
        super(context);
        setTitle(str("morphe_debug_feature_flags_manager_title"));
        setSummary(str("morphe_debug_feature_flags_manager_summary"));
    }

    @Override
    protected FeatureFlags<String> getFeatureFlags() {
        return EnableDebuggingPatch.FEATURE_FLAGS;
    }
}
