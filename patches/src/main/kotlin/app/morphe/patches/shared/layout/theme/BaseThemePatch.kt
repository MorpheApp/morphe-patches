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

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.util.findMutableMethodOf
import java.io.File

private const val THEME_BACKGROUND_EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/theme/ThemeBackgroundPatch;"

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
 * Common utility to generate a notification shape drawable.
 */
fun createNotifDrawable(
    resDir: File,
    resPath: String,
    color: String,
    shape: String,
    hasCorners: Boolean = false,
) {
    val file = resDir.resolve(resPath)
    file.parentFile?.mkdirs()
    val cornersLine = if (hasCorners)
        "\n    <corners android:radius=\"@dimen/new_content_count_radius\" />"
    else ""
    file.writeText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<shape android:shape=\"$shape\"\n" +
                "  xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                "    <solid android:color=\"$color\" />$cornersLine\n" +
                "</shape>"
    )
}

/**
 * Common utility to patch the notification count text color across all API levels.
 */
fun patchCountTextColor(resDir: File, color: String) {
    val targetFolders = listOf("layout-v31", "layout-v26", "layout")

    targetFolders.forEach { folder ->
        val file = resDir.resolve("$folder/new_content_count.xml")
        if (file.exists()) {
            val patchedXml = file.readText().replace(
                Regex("""android:textColor="[^"]+""""),
                """android:textColor="$color""""
            )
            file.writeText(patchedXml)
        }
    }
}

/**
 * Hooks every context of the app so the app resources resolve
 * with the background colors selected in the app settings.
 */
private val themeBackgroundContextHookPatch = bytecodePatch {
    execute {
        var hookedContexts = 0

        classDefForEach { classDef ->
            val mutableClass by lazy { mutableClassDefBy(classDef) }

            classDef.methods.forEach { method ->
                if (method.name != "attachBaseContext" ||
                    method.implementation == null ||
                    method.parameterTypes.singleOrNull()?.toString() != "Landroid/content/Context;"
                ) return@forEach

                mutableClass.findMutableMethodOf(method).addInstructions(
                    0,
                    """
                        invoke-static { p1 }, $THEME_BACKGROUND_EXTENSION_CLASS->wrapContext(Landroid/content/Context;)Landroid/content/Context;
                        move-result-object p1
                    """
                )

                hookedContexts++
            }
        }

        if (hookedContexts == 0) {
            throw PatchException("Could not find a context to hook")
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
    description = "Adds options for theming, and adds a setting to change the app background " +
            "color (defaults to pure black).",
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

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

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

        if (includeLightBackground) {
            addBackgroundColorVariants("mnc", THEME_LIGHT_BACKGROUNDS, lightColorNames())
        }
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

    val resourceDirectory = get("res")

    backgrounds.forEachIndexed { index, color ->
        // The app default has no variant of its own.
        if (color == null) return@forEachIndexed

        // The configuration value of a background is its index plus one,
        // and the extension uses the same numbering.
        val variantDirectory = resourceDirectory.resolve(
            "values-$qualifier" + "%03d".format(index + 1)
        )
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
}
