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
// Player button patches must depend on playerIconStylePatch, which adds the picker.
private val iconStyleSuffixes = listOf("_fluent", "_phosphor", "_phosphor_light", "_phosphor_fill", "_phosphor_duotone", "_ionicons", "_sharp")

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

private const val APP_PLAYER_ICON_DRAWABLE =
    "app.morphe.extension.youtube.videoplayer.AppPlayerIconDrawable"

// App icon, the name its original is moved to, and the wrapper that takes its place.
private val appPlayerIcons = listOf(
    Triple("yt_outline_experimental_player_full_enter_vd_theme_24", "morphe_yt_player_full_enter", "FullscreenEnter"),
    Triple("yt_outline_experimental_player_full_enter_alt_vd_theme_24", "morphe_yt_player_full_enter_alt", "FullscreenEnterAlt"),
    Triple("yt_outline_experimental_player_full_enter_portrait_vd_theme_24", "morphe_yt_player_full_enter_portrait", "FullscreenEnterPortrait"),
    Triple("yt_outline_experimental_player_full_exit_vd_theme_24", "morphe_yt_player_full_exit", "FullscreenExit"),
    Triple("yt_outline_experimental_player_full_exit_alt_vd_theme_24", "morphe_yt_player_full_exit_alt", "FullscreenExitAlt"),
)

// Bitmap versions of the same icons, the bold player shows these. The wrapper falls back to the vector above.
private val appPlayerBitmapIcons = listOf(
    "yt_outline_experimental_player_full_enter_black_24" to "FullscreenEnter",
    "yt_outline_experimental_player_full_enter_alt_black_24" to "FullscreenEnterAlt",
    "yt_outline_experimental_player_full_enter_portrait_black_24" to "FullscreenEnterPortrait",
    "yt_outline_experimental_player_full_exit_black_24" to "FullscreenExit",
    "yt_outline_experimental_player_full_exit_alt_black_24" to "FullscreenExitAlt",
)

/**
 * Replaces an app icon that exists only as density specific bitmaps with a wrapper.
 * A density specific bitmap wins over a default drawable, so every bitmap is removed.
 *
 * @param originalName Name to keep the original bitmaps under, or null to drop them.
 * @return false if the app has no such icon.
 */
internal fun ResourcePatchContext.wrapAppBitmapIcon(
    appName: String,
    wrapperClass: String,
    originalName: String? = null,
): Boolean {
    val bitmaps = get("res", false).listFiles { file ->
        file.isDirectory && file.name.startsWith("drawable")
    }.orEmpty().flatMap { directory ->
        listOf("png", "webp").map { extension -> directory.resolve("$appName.$extension") }
    }.filter { it.exists() }
    if (bitmaps.isEmpty()) return false

    bitmaps.forEach { bitmap ->
        val directory = bitmap.parentFile.name
        if (originalName != null) {
            bitmap.copyTo(get("res/$directory/$originalName.${bitmap.extension}"), overwrite = true)
        }
        delete("res/$directory/${bitmap.name}")
    }
    get("res/drawable/$appName.xml").writeText(appPlayerIconWrapper(wrapperClass))
    return true
}

private fun appPlayerIconWrapper(wrapperClass: String) =
    $$"""
    <?xml version="1.0" encoding="utf-8"?>
    <drawable xmlns:android="http://schemas.android.com/apk/res/android"
        class="$${APP_PLAYER_ICON_DRAWABLE}$$${wrapperClass}" />
    """.trimIndent()

/**
 * Adds the player icon style picker, shared by the player buttons and the swipe controls,
 * and applies the style to the app's own fullscreen button.
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

        // The base icons are the Thin style, the app has no thin fullscreen icon of its own.
        copyPlayerIcons("playericons", "morphe_fullscreen_enter", "morphe_fullscreen_exit")

        // The app loads these by resource id from code, so the wrapper replaces the resource itself.
        val wrapped = appPlayerIcons.filter { (appName, originalName, wrapperClass) ->
            val appIcon = get("res/drawable/$appName.xml")
            // Targets before the bold player do not have these icons.
            if (!appIcon.exists()) return@filter false

            appIcon.copyTo(get("res/drawable/$originalName.xml"), overwrite = true)
            appIcon.writeText(appPlayerIconWrapper(wrapperClass))
            true
        }.map { it.third }

        // A bitmap wrapper falls back to the vector copy, so it is only safe where that copy exists.
        appPlayerBitmapIcons.filter { (_, wrapperClass) -> wrapperClass in wrapped }
            .forEach { (appName, wrapperClass) -> wrapAppBitmapIcon(appName, wrapperClass) }
    }
}
