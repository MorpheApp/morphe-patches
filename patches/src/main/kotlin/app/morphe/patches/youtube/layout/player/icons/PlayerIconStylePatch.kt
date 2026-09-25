/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.icons

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.inputStreamFromBundledResource

// A style does not have to cover every icon, so its variants are copied only when bundled.
// Patches that copy player icons must depend on playerIconStylePatch, which adds the picker.
private val iconStyleSuffixes = listOf("_fluent", "_phosphor", "_sharp")

private fun iconStyleVariants(resourceDirectory: String, baseNames: Array<out String>) =
    baseNames.flatMap { baseName -> iconStyleSuffixes.map { suffix -> "$baseName$suffix.xml" } }
        .filter { file ->
            inputStreamFromBundledResource(resourceDirectory, "drawable/$file")?.use { true } ?: false
        }

/**
 * Copies icons that have no bold variant, such as the swipe controls icons.
 */
internal fun ResourcePatchContext.copyPlayerIcons(resourceDirectory: String, vararg baseNames: String) {
    copyResources(
        resourceDirectory,
        ResourceGroup(
            "drawable",
            *(baseNames.map { "$it.xml" } + iconStyleVariants(resourceDirectory, baseNames)).toTypedArray()
        )
    )
}

/**
 * Copies player button icons, the base and bold icons must exist.
 */
internal fun ResourcePatchContext.copyPlayerButtonIcons(resourceDirectory: String, vararg baseNames: String) {
    copyResources(
        resourceDirectory,
        ResourceGroup(
            "drawable",
            *(baseNames.flatMap { listOf("$it.xml", "${it}_bold.xml") } +
                    iconStyleVariants(resourceDirectory, baseNames)).toTypedArray()
        )
    )
}

/**
 * Copies only the style variants of an icon the app itself provides in the thin and bold styles.
 */
internal fun ResourcePatchContext.copyPlayerIconStyles(resourceDirectory: String, vararg baseNames: String) {
    copyResources(
        resourceDirectory,
        ResourceGroup("drawable", *iconStyleVariants(resourceDirectory, baseNames).toTypedArray())
    )
}

/**
 * Adds the player icon style picker, shared by the player buttons and the swipe controls.
 */
internal val playerIconStylePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference(
                key = "morphe_player_icon_style",
                tag = "app.morphe.extension.youtube.settings.preference.PlayerIconStyleListPreference"
            )
        )
    }
}
