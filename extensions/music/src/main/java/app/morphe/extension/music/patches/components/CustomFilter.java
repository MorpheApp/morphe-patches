/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.CustomFilterBase;

/**
 * YT Music entry point for the shared custom filter. See {@link CustomFilterBase} for the
 * expression syntax reference.
 */
@SuppressWarnings("unused")
public final class CustomFilter extends CustomFilterBase {

    public CustomFilter() {
        super(
                Settings.CUSTOM_FILTER,
                Settings.CUSTOM_FILTER_STRINGS,
                "morphe_custom_filter_toast_invalid_syntax"
        );
    }
}
