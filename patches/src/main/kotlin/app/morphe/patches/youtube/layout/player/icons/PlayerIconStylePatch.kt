/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.icons

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.filePathOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.inputStreamFromBundledResource
import java.io.File
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// A style does not have to cover every icon, so its variants are copied only when bundled.
// Player button patches do not depend on playerIconStylePatch, the styles are only added when it is included.
private val iconStyleSuffixes = listOf(
    "_fluent",
    "_phosphor", "_phosphor_light", "_phosphor_fill", "_phosphor_duotone",
    "_ionicons",
    "_sharp"
)

private fun iconStyleVariants(resourceDirectory: String, baseNames: Array<out String>) =
    baseNames.flatMap { baseName -> iconStyleSuffixes.map { suffix -> "$baseName$suffix.xml" } }
        .filter { file ->
            inputStreamFromBundledResource(resourceDirectory, "drawable/$file")?.use { true } ?: false
        }

private val logger = Logger.getLogger(ResourcePatchContext::class.java.name)

/**
 * User provided icons of the Custom style, by the base name of the icon each one replaces.
 * Only the icons of the included patches are copied, so the file can have more than those.
 */
internal class CustomIcons(path: String) {
    private class Icon(val bytes: ByteArray, val png: Boolean)

    private val icons: Map<String, Icon>
    private val copied = mutableSetOf<String>()

    init {
        val source = File(path.trim())
        if (!source.exists()) throw PatchException("Custom icons file not found: ${source.absolutePath}")

        // Archives made on macOS carry a "._" metadata file next to every file.
        fun isIcon(name: String) = (name.endsWith(".xml") || name.endsWith(".png")) && !name.startsWith("._")

        // A folder works too, which is easier than a zip file when patching with the CLI.
        val files: List<Pair<String, ByteArray>> = if (source.isDirectory) {
            source.walk().filter { it.isFile && isIcon(it.name) }.map { it.name to it.readBytes() }.toList()
        } else {
            ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isIcon(it.name.substringAfterLast('/')) }
                    .map { entry ->
                        entry.name.substringAfterLast('/') to zip.getInputStream(entry).use { it.readBytes() }
                    }.toList()
            }
        }

        icons = files.groupBy { (name, _) -> name.substringBeforeLast('.') }.mapValues { (baseName, found) ->
            // Two files for one icon, either in both formats or in two folders, leave it unclear which one to use.
            if (found.size > 1) {
                throw PatchException("Custom icon $baseName is in the file more than once: ${found.joinToString { it.first }}")
            }
            val (name, bytes) = found.single()
            val png = name.endsWith(".png")
            if (png) validatePng(name, bytes) else validateVector(name, bytes)
            Icon(bytes, png)
        }

        if (icons.isEmpty()) throw PatchException("No icons found in: ${source.absolutePath}")
    }

    fun copy(context: ResourcePatchContext, baseNames: Array<out String>) {
        baseNames.forEach { baseName ->
            val icon = icons[baseName] ?: return@forEach
            // A bitmap of the highest density, the system scales it down for the other densities.
            val file = if (icon.png) {
                context["res/drawable-xxxhdpi/${baseName}_custom.png"].also { it.parentFile.mkdirs() }
            } else {
                context["res/drawable/${baseName}_custom.xml"]
            }
            file.writeBytes(icon.bytes)
            copied += baseName
        }
    }

    /**
     * Logs the icons no included patch asked for, usually a misspelled file name.
     */
    fun warnUnused() {
        val unused = icons.keys.filter { it !in copied }.sorted()
        if (unused.isNotEmpty()) {
            logger.warning("Custom icons not used by any included patch: ${unused.joinToString()}")
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun validatePng(name: String, bytes: ByteArray) {
            // The signature is followed by the IHDR chunk, which starts with the width and height.
            if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
                throw PatchException("Custom icon $name is not a PNG image")
            }
            fun int(offset: Int) = (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[offset + i].toInt() and 0xFF) }
            val width = int(16)
            val height = int(20)
            // The buttons are square, a different shape would be squeezed.
            if (width != height) {
                throw PatchException("Custom icon $name must be square, found ${width}x$height")
            }
        }

        fun validateVector(name: String, bytes: ByteArray) {
            val root = try {
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    // Only the desktop parser knows this feature, the Android one does not resolve entities anyway.
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }.newDocumentBuilder().parse(bytes.inputStream()).documentElement.tagName
            } catch (ex: Exception) {
                throw PatchException("Custom icon $name is not valid XML: ${ex.message}")
            }

            if (root != "vector" && root != "animated-vector") {
                throw PatchException("Custom icon $name must be a vector drawable, found <$root>")
            }
        }
    }
}

/**
 * Icons of the Custom player icon style, or null if the patch option is not set.
 */
internal var customPlayerIcons: CustomIcons? = null
    private set

// Resource directory by base name of the icons of the included patches.
// The player icon style patch copies their styles in its finalize block, once every patch has executed.
private val styledPlayerIcons = LinkedHashMap<String, String>()

/**
 * Copies the style variants and the custom icons of the given icons.
 */
internal fun ResourcePatchContext.copyIconStyles(
    resourceDirectory: String,
    baseNames: Array<out String>,
    customIcons: CustomIcons?,
) {
    copyResources(
        resourceDirectory,
        ResourceGroup("drawable", *iconStyleVariants(resourceDirectory, baseNames).toTypedArray())
    )
    customIcons?.copy(this, baseNames)
}

/**
 * Copies icons that have no bold variant, such as the swipe controls icons.
 */
internal fun ResourcePatchContext.copyPlayerIcons(resourceDirectory: String, vararg baseNames: String) {
    copyResources(resourceDirectory, ResourceGroup("drawable", *baseNames.map { "$it.xml" }.toTypedArray()))
    copyPlayerIconStyles(resourceDirectory, *baseNames)
}

/**
 * Copies player button icons, the base and bold icons must exist.
 */
internal fun ResourcePatchContext.copyPlayerButtonIcons(resourceDirectory: String, vararg baseNames: String) {
    copyResources(
        resourceDirectory,
        ResourceGroup("drawable", *baseNames.flatMap { listOf("$it.xml", "${it}_bold.xml") }.toTypedArray())
    )
    copyPlayerIconStyles(resourceDirectory, *baseNames)
}

/**
 * Adds the style variants of the icons if the player icon style patch is included.
 * Called alone for an icon the app itself provides in the thin and bold styles.
 */
@Suppress("UnusedReceiverParameter")
internal fun ResourcePatchContext.copyPlayerIconStyles(resourceDirectory: String, vararg baseNames: String) {
    baseNames.forEach { styledPlayerIcons[it] = resourceDirectory }
}

internal fun customIconsOptionDescription(example: String) = """
    Zip file with icons to use as the 'Custom' icon style.

    Each icon is named after the icon it replaces, such as '$example'.
    An icon is a vector drawable (.xml), or a white square PNG image (.png, 96x96 pixels is recommended).
    Icons missing from the file keep the 'Automatic' style.
"""

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
val playerIconStylePatch = resourcePatch(
    name = "Player icon style",
    description = "Adds an option to change the style of the player button icons.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    val customIcons by filePathOption(
        key = "customIcons",
        title = "Custom icons",
        description = customIconsOptionDescription("morphe_yt_copy.xml"),
        allowedExtensions = listOf("zip"),
    )

    execute {
        customPlayerIcons = customIcons?.takeIf { it.isNotBlank() }?.let(::CustomIcons)

        PreferenceScreen.PLAYER.addPreferences(
            if (customPlayerIcons == null) {
                ListPreference(
                    key = "morphe_player_icon_style",
                    tag = "app.morphe.extension.youtube.settings.preference.PlayerIconStyleListPreference"
                )
            } else {
                ListPreference(
                    key = "morphe_player_icon_style",
                    tag = "app.morphe.extension.youtube.settings.preference.PlayerIconStyleListPreference",
                    entriesKey = "morphe_player_icon_style_custom_entries",
                    entryValuesKey = "morphe_player_icon_style_custom_entry_values"
                )
            }
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

    finalize {
        styledPlayerIcons.entries.groupBy({ it.value }, { it.key }).forEach { (resourceDirectory, baseNames) ->
            copyIconStyles(resourceDirectory, baseNames.toTypedArray(), customPlayerIcons)
        }
        styledPlayerIcons.clear()

        customPlayerIcons?.warnUnused()
    }
}
