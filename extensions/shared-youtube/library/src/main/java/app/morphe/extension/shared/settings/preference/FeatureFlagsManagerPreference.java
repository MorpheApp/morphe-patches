/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.patches.EnableDebuggingPatch;

/**
 * Feature flags manager of YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public class FeatureFlagsManagerPreference extends AbstractFeatureFlagsManagerPreference<Long> {

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FeatureFlagsManagerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FeatureFlagsManagerPreference(Context context) {
        super(context);
    }

    @Override
    protected FeatureFlags<Long> getFeatureFlags() {
        return EnableDebuggingPatch.FEATURE_FLAGS;
    }
}
