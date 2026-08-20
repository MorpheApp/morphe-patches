/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.theme

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_30_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_DARK_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/theme/ThemePatch;"

private val darkColorNames = {
    THEME_DEFAULT_DARK_COLOR_NAMES + setOf(
        "yt_black_pure",
        "yt_black_pure_opacity80",
        "ytm_color_grey_12",
        "material_grey_800"
    )
}

@Suppress("unused")
val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS,
    darkColorNames = darkColorNames,
    useModernLithoColorHook = {
        is_9_30_or_greater
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
            baseThemeResourcePatch(
                darkColorNames = darkColorNames
            )
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },

    executeBlock = {
        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    "morphe_theme_background_dark",
                    tag = "app.morphe.extension.shared.theme.ThemeBackgroundListPreference"
                ),
                TextPreference(
                    "morphe_theme_background_dark_custom_color",
                    tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                    inputType = InputType.TEXT_CAP_CHARACTERS
                )
            )
        )
    }
)
