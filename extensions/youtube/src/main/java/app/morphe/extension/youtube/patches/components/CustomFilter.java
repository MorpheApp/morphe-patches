/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BaseCustomFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * YouTube-side entry point for the shared custom filter. See {@link BaseCustomFilter} for the
 * expression syntax reference.
 */
@SuppressWarnings("unused")
public final class CustomFilter extends BaseCustomFilter {

    public CustomFilter() {
        super(
                Settings.CUSTOM_FILTER,
                Settings.CUSTOM_FILTER_STRINGS,
                "morphe_custom_filter_toast_invalid_syntax"
        );
    }
}
