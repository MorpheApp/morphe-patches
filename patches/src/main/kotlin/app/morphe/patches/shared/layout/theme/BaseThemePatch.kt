/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.util.forEachChildElement
import app.morphe.util.getNode
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

internal const val THEME_COLOR_EXTENSION_CLASS = "Lapp/morphe/extension/shared/theme/ThemeColorPatch;"

/**
 * Mobile country codes of 100 to 199 are not assigned to any country, so a device never reports
 * one. Every generated variant uses a code of that range. That is the only way the system can be
 * kept from using a variant of its own accord. Anything the system draws, such as the splash
 * screen, is resolved with the configuration of the device and not with the one the app asks for.
 */
private const val UNUSED_MOBILE_COUNTRY_CODE = 100

/**
 * Index of the first color of the 9 bit palette. The indices below it belong to the backgrounds
 * that can be selected by name.
 */
private const val PALETTE_INDEX_OFFSET = 100

/**
 * The value a color channel can have in the 9 bit palette, of the dark and of the light theme.
 * The extension picks an index with the same values, and both must stay identical.
 *
 * A background sits at one end of the range, so the eight values of a channel are placed where the
 * backgrounds of that theme are instead of being spread evenly. A dark background of #0F0F0F would
 * otherwise be shown as pure black, because the nearest even value is 36 away.
 */
private val PALETTE_LEVELS_DARK = intArrayOf(0, 3, 15, 38, 74, 126, 187, 255)
private val PALETTE_LEVELS_LIGHT = intArrayOf(0, 68, 129, 181, 217, 240, 252, 255)

/**
 * A background must only be used by the theme it belongs to. The app uses the light colors as
 * its foreground while it is dark, and the other way around. A light background would otherwise
 * replace the color of the text and the icons of the dark theme.
 *
 * The theme of the app is not the night mode of the device, the app has a setting of its own,
 * so a variant cannot be qualified with 'night'. Instead, the extension asks for the variant of
 * the theme the app shows, and the indices of the two themes never overlap.
 */
private const val THEME_INDEX_OFFSET_DARK = 0
private const val THEME_INDEX_OFFSET_LIGHT = 700

/**
 * Must be identical to the name the extension uses with `FabricatedOverlay#setTargetOverlayable`.
 */
private const val THEME_BACKGROUND_OVERLAYABLE_NAME = "MorpheThemeColor"

/**
 * Name of the theme that draws the splash screen of a background, which the extension asks for
 * with the same numbering.
 */
private const val SPLASH_THEME_NAME = "morphe_splash_theme_"

/**
 * Background colors that can be selected in the app settings.
 *
 * The index of a color is the ordinal of the matching value of the extension enum
 * `ThemeColorPatch.ThemeColorDark`, and the extension selects the color of an index
 * using the 'mcc' resource qualifier. Existing entries cannot be reordered or removed,
 * and new entries can only be appended.
 *
 * A null color is the unpatched color of the app, and has no resource variant.
 */
private val THEME_COLORS_DARK = listOf(
    null,                                   // APP_DEFAULT
    "@android:color/black",                 // PURE_BLACK
    "@android:color/system_neutral1_900",   // MATERIAL_YOU_NEUTRAL
    "@android:color/system_accent1_800",    // MATERIAL_YOU_PRIMARY
    "@android:color/system_accent2_800",    // MATERIAL_YOU_SECONDARY
    "@android:color/system_accent3_800",    // MATERIAL_YOU_TERTIARY
    "#212121",                              // CLASSIC_YOUTUBE
    "#181825",                              // CATPPUCCIN_MOCHA
    "#290025",                              // DARK_PINK
    "#001029",                              // DARK_BLUE
    "#002905",                              // DARK_GREEN
    "#282900",                              // DARK_YELLOW
    "#291800",                              // DARK_ORANGE
    "#290000",                              // DARK_RED
    null,                                   // CUSTOM
)

/**
 * Selected using the 'mnc' resource qualifier.
 *
 * @see THEME_COLORS_DARK
 */
private val THEME_COLORS_LIGHT = listOf(
    null,                                   // APP_DEFAULT
    "@android:color/white",                 // WHITE
    "@android:color/system_neutral1_100",   // MATERIAL_YOU_NEUTRAL
    "@android:color/system_accent1_200",    // MATERIAL_YOU_PRIMARY
    "@android:color/system_accent2_200",    // MATERIAL_YOU_SECONDARY
    "@android:color/system_accent3_200",    // MATERIAL_YOU_TERTIARY
    "#E6E9EF",                              // CATPPUCCIN_LATTE
    "#FCCFF3",                              // LIGHT_PINK
    "#D1E0FF",                              // LIGHT_BLUE
    "#CCFFCC",                              // LIGHT_GREEN
    "#FDFFCC",                              // LIGHT_YELLOW
    "#FFE6CC",                              // LIGHT_ORANGE
    "#FFD6D6",                              // LIGHT_RED
    null,                                   // CUSTOM
)

/**
 * The splash screen is drawn by the system before the app can select a background,
 * so it always uses the color of the default setting value.
 */
internal const val DEFAULT_THEME_COLOR_DARK = "@android:color/black"
internal const val DEFAULT_THEME_COLOR_LIGHT = "@android:color/white"

/**
 * The color the app themes use for the 'ytBaseBackground' attribute, which is the background
 * of the app. Morphe dialogs and settings use the same color so that both always match.
 */
private const val APP_COLOR_NAME_DARK = "yt_sys_color_baseline_mobile_dark_default_base_background"
private const val APP_COLOR_NAME_LIGHT = "yt_sys_color_baseline_mobile_light_default_base_background"

/**
 * The app renamed the color of its background, so the names are listed newest first and the one
 * this app version declares is used.
 */
private val APP_COLOR_NAMES_DARK = listOf(APP_COLOR_NAME_DARK, "yt_black3")
private val APP_COLOR_NAMES_LIGHT = listOf(APP_COLOR_NAME_LIGHT, "yt_white1")

/**
 * The color resources of each theme that this app version has, with the background of the app
 * first. Filled in by the resource patch, which the patch that hands them to the extension
 * depends on.
 */
private var darkColorNames = emptyList<String>()
private var lightColorNames = emptyList<String>()

internal val THEME_DEFAULT_COLOR_NAMES_DARK = setOf(
    "yt_black0", "yt_black1", "yt_black2", "yt_black3", "yt_black4",
    "yt_black1_opacity95", "yt_black1_opacity98",
    "yt_status_bar_background_dark", "material_grey_850",
    APP_COLOR_NAME_DARK,
    "yt_sys_color_baseline_mobile_dark_default_raised_background"
)

internal val THEME_DEFAULT_COLOR_NAMES_LIGHT = setOf(
    "yt_white1", "yt_white2", "yt_white3", "yt_white4",
    "yt_white1_opacity95", "yt_white1_opacity98",
    APP_COLOR_NAME_LIGHT,
    "yt_sys_color_baseline_mobile_light_default_raised_background",
)

/**
 * Hooks every context of the app so the app resources resolve
 * with the background colors selected in the app settings.
 */
private val themeColorContextHookPatch = bytecodePatch {
    execute {
        Fingerprint(
            name = "attachBaseContext",
            parameters = listOf("Landroid/content/Context;"),
            custom = { method, _ ->
                !AccessFlags.STATIC.isSet(method.accessFlags)
            }
        ).matchAll().forEach {
            it.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $THEME_COLOR_EXTENSION_CLASS->wrapContext(Landroid/content/Context;)Landroid/content/Context;
                    move-result-object p1
                """
            )
        }
    }
}

/**
 * Shared theme patch for YouTube and YT Music.
 */
internal fun baseThemePatch(
    extensionClassDescriptor: String,
    includeLightBackground: Boolean = false,
    useModernLithoColorHook: BytecodePatchBuilder.() -> Boolean,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Theme",
    description = "Adds options for theming, and adds a setting to change the app background color.",
) {
    block()

    dependsOn(
        lithoColorHookPatch(useModernLithoColorHook),
        themeColorContextHookPatch
    )

    execute {
        // Morphe dialogs and settings use the background color of the app, and the color
        // resources resolve to the background that is selected in the app settings.
        overrideThemeColors(
            lightColorNames.firstOrNull(),
            darkColorNames.firstOrNull()
                ?: throw PatchException("The resource patch of the theme did not run first")
        )

        // A custom background color has no resource variant to select,
        // so the extension replaces the same colors with an overlay of the app.
        DarkColorResourceNamesFingerprint.method.returnEarly(darkColorNames.joinToString(","))
        if (includeLightBackground) {
            LightColorResourceNamesFingerprint.method.returnEarly(lightColorNames.joinToString(","))
        }

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

/**
 * The color resources of a theme that this app version has, with the background of the app first.
 *
 * A name the app does not declare would be added to the resources as a color of its own, which
 * then exists in the generated variants and nowhere else. The extension shows the color of a
 * background using the first name, and for the background of the app itself there is no variant
 * to read it from, so such a color cannot be resolved at all.
 *
 * @param appColorNames The name the app uses for its own background, newest version first.
 */
private fun themeColorNames(
    appColorNames: List<String>,
    colorNames: Set<String>,
    declaredColors: Set<String>
): List<String> {
    val appColorName = appColorNames.firstOrNull { it in declaredColors }
        ?: throw PatchException("Could not find the background color of the app: $appColorNames")

    return (listOf(appColorName) + colorNames).distinct().filter { it in declaredColors }
}

/**
 * The names of every color the app declares.
 */
private fun ResourcePatchContext.declaredColorNames(): Set<String> {
    val declaredColors = mutableSetOf<String>()

    document("res/values/colors.xml").use { document ->
        document.getNode("resources").forEachChildElement {
            declaredColors += it.getAttribute("name")
        }
    }

    return declaredColors
}

/**
 * Adds a color variant of the app background for every value that can be selected in the app
 * settings. The variants are qualified with 'mcc' and 'mnc' because the app itself ignores both,
 * and the extension selects one of them by overriding the configuration of the app contexts.
 */
internal fun baseThemeResourcePatch(
    colorNamesDark: (() -> Set<String>) = { THEME_DEFAULT_COLOR_NAMES_DARK },
    colorNamesLight: (() -> Set<String>) = { THEME_DEFAULT_COLOR_NAMES_LIGHT },
    includeLightBackground: Boolean = false,
    splashScreenThemeParent: String? = null
) = resourcePatch {
    execute {
        val declaredColors = declaredColorNames()
        darkColorNames = themeColorNames(APP_COLOR_NAMES_DARK, colorNamesDark(), declaredColors)
        lightColorNames = if (includeLightBackground) {
            themeColorNames(APP_COLOR_NAMES_LIGHT, colorNamesLight(), declaredColors)
        } else {
            emptyList()
        }

        addBackgroundColorVariants(THEME_INDEX_OFFSET_DARK, THEME_COLORS_DARK, darkColorNames, true)
        add9BitColorVariants(THEME_INDEX_OFFSET_DARK, PALETTE_LEVELS_DARK, darkColorNames, true)

        if (includeLightBackground) {
            addBackgroundColorVariants(THEME_INDEX_OFFSET_LIGHT, THEME_COLORS_LIGHT, lightColorNames, false)
            add9BitColorVariants(THEME_INDEX_OFFSET_LIGHT, PALETTE_LEVELS_LIGHT, lightColorNames, false)
        }

        declareOverlayableColors(darkColorNames + lightColorNames)

        // An app without a launcher theme keeps the splash screen it draws itself.
        if (splashScreenThemeParent != null) {
            addSplashScreenThemes(splashScreenThemeParent, includeLightBackground)
        }
    }
}

/**
 * Adds a theme for every background, which the system can draw the splash screen of the app with.
 *
 * The splash screen is drawn before the app runs and with the configuration of the device, so the
 * resource variant of the selected background is never used for it. The extension hands one of
 * these themes to the system instead, and the system draws the splash screen with it from then on.
 *
 * @param parentStyle The theme of the launcher activity, so that only the color of it differs.
 */
private fun ResourcePatchContext.addSplashScreenThemes(
    parentStyle: String,
    includeLightBackground: Boolean
) {
    document("res/values/styles.xml").use { document ->
        val resources = document.getNode("resources")

        fun addTheme(index: Int, color: String) {
            val style = document.createElement("style")
            style.setAttribute("name", SPLASH_THEME_NAME + index)
            style.setAttribute("parent", parentStyle)

            // The first is used since Android 12, and the second by everything the app draws
            // until the splash screen is gone.
            arrayOf(
                "android:windowSplashScreenBackground",
                "android:windowBackground"
            ).forEach { name ->
                style.appendChild(
                    document.createElement("item").apply {
                        setAttribute("name", name)
                        textContent = color
                    }
                )
            }

            resources.appendChild(style)
        }

        fun addThemes(
            indexOffset: Int,
            backgrounds: List<String?>,
            levels: IntArray,
            colorNames: List<String>
        ) {
            backgrounds.forEachIndexed { index, color ->
                if (color != null) {
                    addTheme(indexOffset + index + 1, color)
                } else if (index == 0) {
                    // The background of the app itself, which keeps the color the app declares.
                    // The system resolves the theme with the configuration of the device, where
                    // no variant of a background applies, so this is the unpatched color.
                    addTheme(indexOffset + index + 1, "@color/" + colorNames.first())
                }
                // A color the user picks is not known while patching, and the palette below is
                // used for it instead.
            }

            for (index in 0 until 512) {
                addTheme(
                    indexOffset + PALETTE_INDEX_OFFSET + index,
                    paletteColor(levels, index)
                )
            }
        }

        addThemes(THEME_INDEX_OFFSET_DARK, THEME_COLORS_DARK, PALETTE_LEVELS_DARK, darkColorNames)
        if (includeLightBackground) {
            addThemes(
                THEME_INDEX_OFFSET_LIGHT, THEME_COLORS_LIGHT, PALETTE_LEVELS_LIGHT, lightColorNames
            )
        }
    }
}

/**
 * Declares the background colors as overlayable, which an overlay the app registers for itself
 * requires. Without this the system rejects the overlay of a custom background color.
 */
private fun ResourcePatchContext.declareOverlayableColors(colorNames: List<String>) {
    // A policy item is resolved while encoding, and every name is one the app declares.
    if (colorNames.isEmpty()) {
        throw PatchException("Could not find any background color to declare as overlayable")
    }

    val overlayable = buildString {
        appendLine("    <overlayable name=\"$THEME_BACKGROUND_OVERLAYABLE_NAME\">")
        appendLine("        <policy type=\"public\">")
        colorNames.forEach { name ->
            appendLine("            <item type=\"color\" name=\"$name\" />")
        }
        appendLine("        </policy>")
        appendLine("    </overlayable>")
    }

    // The app can declare overlayables of its own, and those must be kept.
    val overlayableFile = get("res").resolve("values/overlayable.xml")
    if (overlayableFile.exists()) {
        overlayableFile.writeText(
            overlayableFile.readText().replaceFirst("</resources>", "$overlayable</resources>")
        )
    } else {
        overlayableFile.writeText(
            buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                appendLine("<resources>")
                append(overlayable)
                appendLine("</resources>")
            }
        )
    }
}

private fun ResourcePatchContext.addBackgroundColorVariants(
    indexOffset: Int,
    backgrounds: List<String?>,
    colorNames: List<String>,
    isDark: Boolean
) {
    if (colorNames.isEmpty()) {
        throw PatchException("No color to replace for the app background")
    }

    backgrounds.forEachIndexed { index, color ->
        // The app default has no variant of its own.
        if (color == null) return@forEachIndexed

        // The configuration value of a background is its index plus one,
        // and the extension uses the same numbering.
        writeBackgroundColorVariant(indexOffset + index + 1, color, colorNames, isDark)
    }
}

private fun ResourcePatchContext.add9BitColorVariants(
    indexOffset: Int,
    levels: IntArray,
    colorNames: List<String>,
    isDark: Boolean
) {
    for (index in 0 until 512) {
        writeBackgroundColorVariant(
            indexOffset + PALETTE_INDEX_OFFSET + index, paletteColor(levels, index), colorNames, isDark
        )
    }
}

/**
 * The color of a value of the 9 bit palette, which the extension picks the index of.
 */
private fun paletteColor(levels: IntArray, index: Int) = "#%02X%02X%02X".format(
    levels[(index shr 6) and 0x7],
    levels[(index shr 3) and 0x7],
    levels[index and 0x7]
)

private fun ResourcePatchContext.writeBackgroundColorVariant(
    index: Int,
    color: String,
    colorNames: List<String>,
    isDark: Boolean
) {
    // The mobile country code of a variant is never one a device can have, so the resource
    // system uses a variant only when the app asks for it. The extension uses the same encoding.
    val qualifier = if (isDark) {
        "mcc%03d".format(UNUSED_MOBILE_COUNTRY_CODE + (index - THEME_INDEX_OFFSET_DARK))
    } else {
        "mnc%03d".format(1 + (index - THEME_INDEX_OFFSET_LIGHT))
    }

    val variantDirectory = get("res").resolve("values-$qualifier")
    variantDirectory.mkdirs()

    variantDirectory.resolve("colors.xml").writeText(
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            colorNames.forEach { name ->
                appendLine("    <color name=\"$name\">$color</color>")
            }
            appendLine("</resources>")
        }
    )
}
