/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.settings.preference

/**
 * A switch preference.
 *
 * @param key The preference key. If null, other parameters must be specified.
 * @param titleKey The preference title key.
 * @param icon The preference icon resource name.
 * @param layout Layout declaration.
 * @param tag The preference tag.
 * @param summary Whether to use a summary.
 */
class SwitchPreference(
    key: String? = null,
    titleKey: String = "${key}_title",
    summary: Boolean = false,
    tag: String = "app.morphe.extension.shared.settings.preference.BooleanSettingPreference",
    icon: String? = null,
    iconBold: String? = null,
    layout: String? = null
) : BasePreference(key, titleKey, if (summary) "${key}_summary" else null, icon, iconBold, layout, tag)
