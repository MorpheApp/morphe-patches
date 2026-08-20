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
import app.morphe.util.asSequence
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import org.w3c.dom.Element

internal const val THEME_BACKGROUND_EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/theme/ThemeBackgroundPatch;"

/**
 * Must be identical to the name the extension uses with `FabricatedOverlay#setTargetOverlayable`.
 */
private const val THEME_BACKGROUND_OVERLAYABLE_NAME = "MorpheThemeBackground"

/**
 * Background colors that can be selected in the app settings.
 *
 * The index of a color is the ordinal of the matching value of the extension enum
 * `ThemeBackgroundPatch.DarkThemeBackground`, and the extension selects the color of an index
 * using the 'mcc' resource qualifier. Existing entries cannot be reordered or removed,
 * and new entries can only be appended.
 *
 * A null color is the unpatched color of the app, and has no resource variant.
 */
private val THEME_DARK_BACKGROUNDS = listOf(
    null,                                   // APP_DEFAULT
    "@android:color/black",                 // PURE_BLACK
    "@android:color/system_neutral1_900",   // MATERIAL_YOU_NEUTRAL
    "@android:color/system_accent1_800",    // MATERIAL_YOU_PRIMARY
    "@android:color/system_accent2_800",    // MATERIAL_YOU_SECONDARY
    "@android:color/system_accent3_800",    // MATERIAL_YOU_TERTIARY
    "#0F0F0F",                              // MODERN_YOUTUBE
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
 * @see THEME_DARK_BACKGROUNDS
 */
private val THEME_LIGHT_BACKGROUNDS = listOf(
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
internal const val DEFAULT_DARK_THEME_BACKGROUND_COLOR = "@android:color/black"
internal const val DEFAULT_LIGHT_THEME_BACKGROUND_COLOR = "@android:color/white"

/**
 * The color the app themes use for the 'ytBaseBackground' attribute, which is the background
 * of the app. Morphe dialogs and settings use the same color so that both always match.
 */
private const val APP_DARK_BACKGROUND_COLOR_NAME = "yt_sys_color_baseline_mobile_dark_default_base_background"
private const val APP_LIGHT_BACKGROUND_COLOR_NAME = "yt_sys_color_baseline_mobile_light_default_base_background"

internal val THEME_DEFAULT_DARK_COLOR_NAMES = setOf(
    "yt_black0", "yt_black1", "yt_black2", "yt_black3", "yt_black4",
    "yt_black1_opacity95", "yt_black1_opacity98",
    "yt_status_bar_background_dark", "material_grey_850",
    APP_DARK_BACKGROUND_COLOR_NAME,
    "yt_sys_color_baseline_mobile_dark_default_raised_background"
)

internal val THEME_DEFAULT_LIGHT_COLOR_NAMES = setOf(
    "yt_white1", "yt_white2", "yt_white3", "yt_white4",
    "yt_white1_opacity95", "yt_white1_opacity98",
    APP_LIGHT_BACKGROUND_COLOR_NAME,
    "yt_sys_color_baseline_mobile_light_default_raised_background",
)

/**
 * Hooks every context of the app so the app resources resolve
 * with the background colors selected in the app settings.
 */
private val themeBackgroundContextHookPatch = bytecodePatch {
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
                    invoke-static { p1 }, $THEME_BACKGROUND_EXTENSION_CLASS->wrapContext(Landroid/content/Context;)Landroid/content/Context;
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
    darkColorNames: (() -> Set<String>) = { THEME_DEFAULT_DARK_COLOR_NAMES },
    lightColorNames: (() -> Set<String>) = { THEME_DEFAULT_LIGHT_COLOR_NAMES },
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
        themeBackgroundContextHookPatch
    )

    execute {
        // Morphe dialogs and settings use the background color of the app, and the color
        // resources resolve to the background that is selected in the app settings.
        overrideThemeColors(
            if (includeLightBackground) APP_LIGHT_BACKGROUND_COLOR_NAME else null,
            APP_DARK_BACKGROUND_COLOR_NAME
        )

        // A custom background color has no resource variant to select,
        // so the extension replaces the same colors with an overlay of the app.
        DarkColorResourceNamesFingerprint.method.returnEarly(
            colorResourceNames(APP_DARK_BACKGROUND_COLOR_NAME, darkColorNames())
        )
        if (includeLightBackground) {
            LightColorResourceNamesFingerprint.method.returnEarly(
                colorResourceNames(APP_LIGHT_BACKGROUND_COLOR_NAME, lightColorNames())
            )
        }

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

/**
 * The extension shows the color of a background in the app settings using the first name,
 * so the color the app uses for the background itself must be first.
 */
private fun colorResourceNames(appBackgroundColorName: String, colorNames: Set<String>) =
    (listOf(appBackgroundColorName) + colorNames).distinct().joinToString(",")

/**
 * Adds a color variant of the app background for every value that can be selected in the app
 * settings. The variants are qualified with 'mcc' and 'mnc' because the app itself ignores both,
 * and the extension selects one of them by overriding the configuration of the app contexts.
 */
internal fun baseThemeResourcePatch(
    darkColorNames: (() -> Set<String>) = { THEME_DEFAULT_DARK_COLOR_NAMES },
    lightColorNames: (() -> Set<String>) = { THEME_DEFAULT_LIGHT_COLOR_NAMES },
    includeLightBackground: Boolean = false
) = resourcePatch {
    execute {
        addBackgroundColorVariants("mcc", THEME_DARK_BACKGROUNDS, darkColorNames())
        add9BitColorVariants("mcc", darkColorNames())

        if (includeLightBackground) {
            addBackgroundColorVariants("mnc", THEME_LIGHT_BACKGROUNDS, lightColorNames())
            add9BitColorVariants("mnc", lightColorNames())
        }

        declareOverlayableColors(
            if (includeLightBackground) darkColorNames() + lightColorNames() else darkColorNames()
        )
    }
}

/**
 * Declares the background colors as overlayable, which an overlay the app registers for itself
 * requires. Without this the system rejects the overlay of a custom background color.
 */
private fun ResourcePatchContext.declareOverlayableColors(colorNames: Set<String>) {
    // A policy item is resolved while encoding, so a color that this app version
    // does not have must not be declared.
    val declaredColors = mutableSetOf<String>()
    document("res/values/colors.xml").use { document ->
        (document.getElementsByTagName("resources").item(0) as Element)
            .childNodes.asSequence()
            .filterIsInstance<Element>()
            .forEach { declaredColors += it.getAttribute("name") }
    }

    val overlayableColors = colorNames.filter { it in declaredColors }
    if (overlayableColors.isEmpty()) {
        throw PatchException("Could not find any background color to declare as overlayable")
    }

    val overlayable = buildString {
        appendLine("    <overlayable name=\"$THEME_BACKGROUND_OVERLAYABLE_NAME\">")
        appendLine("        <policy type=\"public\">")
        overlayableColors.forEach { name ->
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
    qualifier: String,
    backgrounds: List<String?>,
    colorNames: Set<String>
) {
    if (colorNames.isEmpty()) {
        throw PatchException("No color to replace for the app background")
    }

    backgrounds.forEachIndexed { index, color ->
        // The app default has no variant of its own.
        if (color == null) return@forEachIndexed

        // The configuration value of a background is its index plus one,
        // and the extension uses the same numbering.
        val variantValue = "%03d".format(index + 1)
        writeBackgroundColorVariant(qualifier, variantValue, color, colorNames)
    }
}

private fun ResourcePatchContext.add9BitColorVariants(
    qualifier: String,
    colorNames: Set<String>
) {
    for (index in 0 until 512) {
        val r3 = (index shr 6) and 0x7
        val g3 = (index shr 3) and 0x7
        val b3 = index and 0x7

        val r = Math.round(r3 * 255f / 7f)
        val g = Math.round(g3 * 255f / 7f)
        val b = Math.round(b3 * 255f / 7f)

        val color = "#%02X%02X%02X".format(r, g, b)
        val variantValue = "%03d".format(100 + index)
        writeBackgroundColorVariant(qualifier, variantValue, color, colorNames)
    }
}

private fun ResourcePatchContext.writeBackgroundColorVariant(
    qualifier: String,
    variantValue: String,
    color: String,
    colorNames: Set<String>
) {
    val variantDirectory = get("res").resolve("values-$qualifier$variantValue")
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
