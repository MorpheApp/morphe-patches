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
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.util.childElementsSequence
import app.morphe.util.forEachChildElement
import app.morphe.util.getNode
import app.morphe.util.inputStreamFromBundledResource
import app.morphe.util.returnEarly
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import java.util.Locale

internal const val THEME_COLOR_EXTENSION_CLASS = "Lapp/morphe/extension/shared/theme/ThemeColorPatch;"

/**
 * A mobile country code and a mobile network code are three digits, so a device never reports one
 * above 999. Every variant uses a code above it, otherwise the system picks one of them while it
 * draws the splash screen, which is resolved with the configuration of the device.
 */
private const val UNREACHABLE_MOBILE_CODE = 1000

/**
 * Index of the first color of the 9 bit palette. The indices below it belong to the colors
 * that can be selected by name.
 */
private const val PALETTE_INDEX_OFFSET = 100

/**
 * The value a color channel can have in the 9 bit palette, of the dark and of the light theme.
 * The extension picks an index with the same values, and both must stay identical.
 *
 * A color sits at one end of the range, so the eight values of a channel are placed where the
 * color of that theme are instead of being spread evenly. A dark color of #0F0F0F would
 * otherwise be shown as pure black, because the nearest even value is 36 away.
 */
private val PALETTE_LEVELS_DARK = intArrayOf(0, 3, 15, 38, 74, 126, 187, 255)
private val PALETTE_LEVELS_LIGHT = intArrayOf(0, 68, 129, 181, 217, 240, 252, 255)

/**
 * A color must only be used by the theme it belongs to. The app uses the light colors as
 * its foreground while it is dark, and the other way around. A light color would otherwise
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
private const val THEME_COLOR_OVERLAYABLE_NAME = "MorpheThemeColor"

/**
 * Name of the theme that draws the splash screen of a theme, which the extension asks for
 * with the same numbering.
 */
private const val SPLASH_THEME_NAME = "morphe_splash_theme_"

/**
 * A color that can be selected in the app settings.
 *
 * @param value Name of the matching value of the extension enum `ThemeColorPatch.ThemeColorDark`.
 * @param color The color, or null for the unpatched color of the app, which has no variant.
 */
private class ThemeColor(val value: String, val color: String?)

/**
 * Colors that can be selected in the app settings, which the extension selects the color of
 * with the 'mcc' resource qualifier. The Theme patch verifies that this list, the extension enum
 * and the setting entries are all in the same order.
 */
private val THEME_COLORS_DARK = listOf(
    ThemeColor("APP_DEFAULT", null),
    ThemeColor("PURE_BLACK", "@android:color/black"),
    ThemeColor("MATERIAL_YOU_PURE_BLACK", "@android:color/black"),
    ThemeColor("MATERIAL_YOU_NEUTRAL", "@android:color/system_neutral1_900"),
    ThemeColor("MATERIAL_YOU_PRIMARY", "@android:color/system_accent1_800"),
    ThemeColor("MATERIAL_YOU_SECONDARY", "@android:color/system_accent2_800"),
    ThemeColor("MATERIAL_YOU_TERTIARY", "@android:color/system_accent3_800"),
    ThemeColor("CLASSIC_YOUTUBE", "#212121"),
    ThemeColor("CATPPUCCIN_MOCHA", "#181825"),
    ThemeColor("DARK_PINK", "#290025"),
    ThemeColor("DARK_BLUE", "#001029"),
    ThemeColor("DARK_GREEN", "#002905"),
    ThemeColor("DARK_YELLOW", "#282900"),
    ThemeColor("DARK_ORANGE", "#291800"),
    ThemeColor("DARK_RED", "#290000"),
    ThemeColor("CUSTOM", null),
)

/**
 * Selected using the 'mnc' resource qualifier.
 *
 * @see THEME_COLORS_DARK
 */
private val THEME_COLORS_LIGHT = listOf(
    ThemeColor("APP_DEFAULT", null),
    ThemeColor("WHITE", "@android:color/white"),
    ThemeColor("MATERIAL_YOU_WHITE", "@android:color/white"),
    ThemeColor("MATERIAL_YOU_NEUTRAL", "@android:color/system_neutral1_100"),
    ThemeColor("MATERIAL_YOU_PRIMARY", "@android:color/system_accent1_200"),
    ThemeColor("MATERIAL_YOU_SECONDARY", "@android:color/system_accent2_200"),
    ThemeColor("MATERIAL_YOU_TERTIARY", "@android:color/system_accent3_200"),
    ThemeColor("CATPPUCCIN_LATTE", "#E6E9EF"),
    ThemeColor("LIGHT_PINK", "#FCCFF3"),
    ThemeColor("LIGHT_BLUE", "#D1E0FF"),
    ThemeColor("LIGHT_GREEN", "#CCFFCC"),
    ThemeColor("LIGHT_YELLOW", "#FDFFCC"),
    ThemeColor("LIGHT_ORANGE", "#FFE6CC"),
    ThemeColor("LIGHT_RED", "#FFD6D6"),
    ThemeColor("CUSTOM", null),
)

/**
 * Every theme color of the app is an alias of this one, so that a resource variant declares
 * a single entry instead of one for every name.
 */
private const val THEME_COLOR_DARK = "morphe_theme_color_dark"
private const val THEME_COLOR_LIGHT = "morphe_theme_color_light"

/**
 * The splash screen is drawn by the system before the app can select a color,
 * so it always uses the color of the default setting value.
 */
internal const val DEFAULT_THEME_COLOR_DARK = "@android:color/black"
internal const val DEFAULT_THEME_COLOR_LIGHT = "@android:color/white"

/**
 * The default of both color options. It is not a color because an option can only hold a string.
 */
private const val THEME_COLOR_IN_APP = "in-app"

private const val THEME_COLOR_OPTION_DESCRIPTION = "Can be a hex color (#RRGGBB) or a color " +
        "resource reference. Setting a color of either theme applies both while patching and " +
        "removes the in-app theme color settings."

/**
 * Dark theme color of the YouTube and YT Music Theme patch.
 *
 * A color that is set here is what a user of an old device needs: the splash screen the system
 * draws uses it on every Android version, and nothing is generated for the colors that
 * could otherwise be selected in the app settings.
 */
internal val darkThemeColorOption = colorOption(
    key = "darkThemeColor",
    default = THEME_COLOR_IN_APP,
    values = mapOf(
        "Change in the app" to THEME_COLOR_IN_APP,
        "Pure black" to "@android:color/black",
        "Material You (Neutral)" to "@android:color/system_neutral1_900",
        "Material You (Primary)" to "@android:color/system_accent1_800",
        "Material You (Secondary)" to "@android:color/system_accent2_800",
        "Material You (Tertiary)" to "@android:color/system_accent3_800",
        "Modern YouTube" to "#0F0F0F",
        "Classic YouTube" to "#212121",
        "Catppuccin (Mocha)" to "#181825",
        "Dark pink" to "#290025",
        "Dark blue" to "#001029",
        "Dark green" to "#002905",
        "Dark yellow" to "#282900",
        "Dark orange" to "#291800",
        "Dark red" to "#290000",
    ),
    title = "Dark theme color",
    description = THEME_COLOR_OPTION_DESCRIPTION
)

/**
 * Light theme color of the YouTube Theme patch.
 *
 * @see darkThemeColorOption
 */
internal val lightThemeColorOption = colorOption(
    key = "lightThemeColor",
    default = THEME_COLOR_IN_APP,
    values = mapOf(
        "Change in the app" to THEME_COLOR_IN_APP,
        "White" to "@android:color/white",
        "Material You (Neutral)" to "@android:color/system_neutral1_100",
        "Material You (Primary)" to "@android:color/system_accent1_200",
        "Material You (Secondary)" to "@android:color/system_accent2_200",
        "Material You (Tertiary)" to "@android:color/system_accent3_200",
        "Catppuccin (Latte)" to "#E6E9EF",
        "Light pink" to "#FCCFF3",
        "Light blue" to "#D1E0FF",
        "Light green" to "#CCFFCC",
        "Light yellow" to "#FDFFCC",
        "Light orange" to "#FFE6CC",
        "Light red" to "#FFD6D6",
    ),
    title = "Light theme color",
    description = THEME_COLOR_OPTION_DESCRIPTION
)

/**
 * Setting the color of one theme of an app that has two applies both, otherwise one theme could
 * still be changed while the app runs and the other one not.
 */
internal val usePatchedThemeColor: Boolean
    get() = darkThemeColorOption.value != THEME_COLOR_IN_APP ||
            lightThemeColorOption.value != THEME_COLOR_IN_APP

internal val patchedThemeColorDark: String
    get() = patchedThemeColor(
        darkThemeColorOption.value, appThemeColorDark, DEFAULT_THEME_COLOR_DARK
    )

internal val patchedThemeColorLight: String
    get() = patchedThemeColor(
        lightThemeColorOption.value, appThemeColorLight, DEFAULT_THEME_COLOR_LIGHT
    )

/**
 * @param appColor The color the app uses for the color of this theme, which the resource
 *                 patch fills in.
 * @param default  The value the app setting defaults to, which the splash screen has to use
 *                 while the color can still be changed in the app.
 */
private fun patchedThemeColor(value: String?, appColor: String?, default: String) = when {
    value != null && value != THEME_COLOR_IN_APP -> value
    // A theme that is left to the app keeps the color of the app, so that applying the color of
    // one theme does not change the other one as well.
    usePatchedThemeColor -> appColor ?: default
    else -> default
}

/**
 * @param colorString #AARRGGBB, #RRGGBB, or an Android color resource name.
 */
private fun validateColorName(colorString: String): Boolean {
    if (colorString.startsWith("#")) {
        val hex = colorString.substring(1).uppercase(Locale.US)

        if (hex.length == 8) {
            // Transparent colors will crash the app.
            if (hex[0] != 'F' || hex[1] != 'F') {
                return false
            }
        } else if (hex.length != 6) {
            return false
        }

        return hex.all { it.isDigit() || it in 'A'..'F' }
    }

    if (colorString.startsWith("@android:color/")) {
        // Cannot easily validate Android built-in colors, so assume it's a correct color.
        return true
    }

    // Allow any color name, because if it's invalid it will
    // throw an exception during resource compilation.
    return colorString.startsWith("@color/")
}

/**
 * The color the app themes use for the 'ytBaseBackground' attribute, which is the color
 * of the app. Morphe dialogs and settings use the same color so that both always match.
 */
private const val APP_COLOR_NAME_DARK = "yt_sys_color_baseline_mobile_dark_default_base_background"
private const val APP_COLOR_NAME_LIGHT = "yt_sys_color_baseline_mobile_light_default_base_background"

/**
 * The app renamed the colors, so the names are listed newest first and the one
 * this app version declares is used.
 */
private val APP_COLOR_NAMES_DARK = listOf(APP_COLOR_NAME_DARK, "yt_black3")
private val APP_COLOR_NAMES_LIGHT = listOf(APP_COLOR_NAME_LIGHT, "yt_white1")

/**
 * The color resources of each theme that this app version has, with the color of the app
 * first. Filled in by the resource patch, which the patch that hands them to the extension
 * depends on.
 */
private var darkColorNames = emptyList<String>()
private var lightColorNames = emptyList<String>()

/**
 * The color the unpatched app uses for the color of each theme, filled in with the names.
 */
private var appThemeColorDark: String? = null
private var appThemeColorLight: String? = null

/**
 * The alias colors of each theme that this app version has.
 */
private var darkAliasNames = emptyList<String>()
private var lightAliasNames = emptyList<String>()

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
 * with the theme colors selected in the app settings.
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
    includeLightColor: Boolean = false,
    useModernLithoColorHook: BytecodePatchBuilder.() -> Boolean,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Theme",
    description = "Adds options for theming, and settings to change the app foreground and background colors.",
) {
    darkThemeColorOption()

    if (includeLightColor) {
        lightThemeColorOption()
    }

    block()

    dependsOn(
        lithoColorHookPatch(useModernLithoColorHook),
        themeColorContextHookPatch
    )

    execute {
        if (darkColorNames.isEmpty()) {
            throw PatchException("The resource patch of the theme did not run first")
        }

        setExtensionIsPatchIncluded(THEME_COLOR_EXTENSION_CLASS)

        if (usePatchedThemeColor) {
            overrideThemeColors(
                if (includeLightColor) patchedThemeColorLight else null,
                patchedThemeColorDark
            )

            PatchedThemeColorDarkFingerprint.method.returnEarly(patchedThemeColorDark)
            if (includeLightColor) {
                PatchedThemeColorLightFingerprint.method
                    .returnEarly(patchedThemeColorLight)
            }
        } else {
            verifyColors("ThemeColorDark", THEME_COLORS_DARK)
            if (includeLightColor) {
                verifyColors("ThemeColorLight", THEME_COLORS_LIGHT)
            }

            // Morphe dialogs and settings use the theme color of the app, and the color
            // resources resolve to the color that is selected in the app settings.
            overrideThemeColors(
                if (includeLightColor) THEME_COLOR_LIGHT else null,
                THEME_COLOR_DARK
            )

            // A custom theme color has no resource variant to select,
            // so the extension replaces the same colors with an overlay of the app.
            DarkColorResourceNamesFingerprint.method.returnEarly(darkAliasNames.joinToString(","))
            if (includeLightColor) {
                LightColorResourceNamesFingerprint.method
                    .returnEarly(lightAliasNames.joinToString(","))
            }
        }

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

/**
 * Fails the patch if a color exists in the extension enum and not in the list of the patch,
 * or the other way around. A color is selected by its position in both, so one that is added
 * to only one of them silently shifts every color that follows it.
 */
private fun BytecodePatchContext.verifyColors(
    enumName: String,
    colors: List<ThemeColor>
) {
    val enumType = THEME_COLOR_EXTENSION_CLASS.dropLast(1) + '$' + enumName + ";"

    // A value of an enum is a static field of the type of the enum itself.
    val declared = classDefBy(enumType).fields
        .filter { it.type == enumType }
        .map { it.name }
        .toSet()

    val expected = colors.mapTo(mutableSetOf()) { it.value }
    if (declared != expected) {
        throw PatchException(
            "Colors of $enumName do not match the patch. " +
                    "Only in the patch: ${expected - declared}. " +
                    "Only in the extension: ${declared - expected}"
        )
    }
}

/**
 * The color resources of a theme that this app version has, with the color of the app first.
 *
 * A name the app does not declare would be added to the resources as a color of its own, which
 * then exists in the generated variants and nowhere else. The extension shows the color of a
 * theme using the first name, and for the color of the app itself there is no variant
 * to read it from, so such a color cannot be resolved at all.
 *
 * @param appColorNames The name the app uses for its own color, newest version first.
 */
private fun themeColorNames(
    appColorNames: List<String>,
    colorNames: Set<String>,
    declaredColors: Map<String, String>
): List<String> {
    val appColorName = appColorNames.firstOrNull { it in declaredColors }
        ?: throw PatchException("Could not find the theme color of the app: $appColorNames")

    return (listOf(appColorName) + colorNames).distinct().filter { it in declaredColors }
}

/**
 * The names and values of every color the app declares.
 */
private fun ResourcePatchContext.declaredColors(colorFiles: List<String>): Map<String, String> {
    val declaredColors = LinkedHashMap<String, String>()

    colorFiles.forEach { path ->
        document(path).use { document ->
            document.getNode("resources").forEachChildElement {
                declaredColors[it.getAttribute("name")] = it.textContent
            }
        }
    }

    return declaredColors
}

private fun ResourcePatchContext.colorFiles(): List<String> {
    val colorFiles = mutableListOf<String>()
    val resDir = get("res")
    if (!resDir.exists()) return colorFiles

    resDir.listFiles()?.forEach { dir ->
        if (dir.isDirectory && dir.name.startsWith("values")) {
            val colorsFile = dir.resolve("colors.xml")
            if (colorsFile.exists()) {
                colorFiles.add("res/${dir.name}/colors.xml")
            }
        }
    }
    return colorFiles
}

/**
 * @param colorValues All colors the app declares.
 */
private fun resolveColorValue(color: String, colorValues: Map<String, String>): String {
    var current = color
    while (current.startsWith("@color/")) {
        val name = current.substring(7)
        current = colorValues[name] ?: break
    }
    return current
}

/**
 * Adds a color variant of the app color for every value that can be selected in the app
 * settings. The variants are qualified with 'mcc' and 'mnc' because the app itself ignores both,
 * and the extension selects one of them by overriding the configuration of the app contexts.
 */
internal fun baseThemeResourcePatch(
    colorNamesDark: (() -> Set<String>) = { THEME_DEFAULT_COLOR_NAMES_DARK },
    colorNamesLight: (() -> Set<String>) = { THEME_DEFAULT_COLOR_NAMES_LIGHT },
    includeLightColor: Boolean = false,
    splashScreenThemeParent: String? = null
) = resourcePatch {
    execute {
        val colorFiles = colorFiles()
        val declaredColors = declaredColors(colorFiles)
        darkColorNames = themeColorNames(APP_COLOR_NAMES_DARK, colorNamesDark(), declaredColors)
        lightColorNames = if (includeLightColor) {
            themeColorNames(APP_COLOR_NAMES_LIGHT, colorNamesLight(), declaredColors)
        } else {
            emptyList()
        }

        appThemeColorDark = declaredColors[darkColorNames.first()]
        appThemeColorLight = lightColorNames.firstOrNull()?.let { declaredColors[it] }

        // A color that is set while patching is the only color the app can have,
        // so none of the variants and themes below are of any use.
        if (usePatchedThemeColor) {
            replaceColors(colorFiles, declaredColors, includeLightColor)
            return@execute
        }

        verifySettingEntries(
            "morphe_theme_color_dark", "values/shared-youtube/arrays.xml",
            THEME_COLORS_DARK
        )

        if (includeLightColor) {
            verifySettingEntries(
                "morphe_theme_color_light", "values/youtube/arrays.xml",
                THEME_COLORS_LIGHT
            )
        }

        val aliasAlphas = addColorAliases(colorFiles, declaredColors, includeLightColor)
        
        val darkAliasAlphas = aliasAlphas.filterKeys { isDarkThemeColorAlias(it) }
        darkAliasNames = darkAliasAlphas.keys.toList()
        addColorVariants(
            THEME_INDEX_OFFSET_DARK, THEME_COLORS_DARK, PALETTE_LEVELS_DARK,
            darkAliasAlphas, true
        )

        if (includeLightColor) {
            val lightAliasAlphas = aliasAlphas.filterKeys { !isDarkThemeColorAlias(it) }
            lightAliasNames = lightAliasAlphas.keys.toList()
            addColorVariants(
                THEME_INDEX_OFFSET_LIGHT, THEME_COLORS_LIGHT, PALETTE_LEVELS_LIGHT,
                lightAliasAlphas, false
            )
        }

        declareOverlayableColors(
            if (includeLightColor) {
                listOf(THEME_COLOR_DARK, THEME_COLOR_LIGHT)
            } else {
                listOf(THEME_COLOR_DARK)
            }
        )

        // An app without a launcher theme keeps the splash screen it draws itself.
        if (splashScreenThemeParent != null) {
            addSplashScreenThemes(splashScreenThemeParent, includeLightColor)
        }
    }
}

/**
 * Fails the patch if the setting entries of a color do not match the colors of the
 * patch. The app settings show the color of a color by its position in both lists.
 */
private fun ResourcePatchContext.verifySettingEntries(
    key: String,
    resourcePath: String,
    colors: List<ThemeColor>
) {
    val stream = inputStreamFromBundledResource("addresources", resourcePath)
        ?: throw PatchException("Could not find the setting entries: $resourcePath")

    val arrays = mutableMapOf<String, List<String>>()
    stream.use {
        document(it).use { document ->
            document.getNode("resources").forEachChildElement { array ->
                arrays[array.getAttribute("name")] =
                    array.childElementsSequence().map { item -> item.textContent }.toList()
            }
        }
    }

    val values = colors.map { it.value }
    if (arrays["${key}_entry_values"] != values) {
        throw PatchException("The entry values of $key do not match the patch: $values")
    }

    // The name of a color is shown by the position it has in the values.
    if (arrays["${key}_entries"]?.size != values.size) {
        throw PatchException("The entries of $key do not match the entry values")
    }
}

/**
 * Gives the theme colors of the app the color that is set as a patch option, which is what
 * the Theme patch did before the color could be changed in the app settings.
 */
private fun ResourcePatchContext.replaceColors(
    colorFiles: List<String>,
    declaredColors: Map<String, String>,
    includeLightCOlor: Boolean
) {
    val darkColor = patchedThemeColorDark
    if (!validateColorName(darkColor)) {
        throw PatchException("Invalid dark theme color: $darkColor")
    }

    val lightColor = patchedThemeColorLight
    if (includeLightCOlor && !validateColorName(lightColor)) {
        throw PatchException("Invalid light theme color: $lightColor")
    }

    colorFiles.forEach { path ->
        document(path).use { document ->
            document.getNode("resources").forEachChildElement { node ->
                val name = node.getAttribute("name")
                val color = when (name) {
                    in darkColorNames -> darkColor
                    in lightColorNames -> lightColor
                    else -> return@forEachChildElement
                }

                val alpha = parseAlpha(resolveColorValue(node.textContent, declaredColors))
                node.textContent = applyAlpha(color, alpha)
            }
        }
    }
}

/**
 * Adds a theme for every color, which the system can draw the splash screen of the app with.
 *
 * The splash screen is drawn before the app runs and with the configuration of the device, so the
 * resource variant of the selected color is never used for it. The extension hands one of
 * these themes to the system instead, and the system draws the splash screen with it from then on.
 *
 * @param parentStyle The theme of the launcher activity, so that only the color of it differs.
 */
private fun ResourcePatchContext.addSplashScreenThemes(
    parentStyle: String,
    includeLightColor: Boolean
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
            colors: List<ThemeColor>,
            levels: IntArray,
            aliasName: String
        ) {
            // The system resolves the splash screen with the configuration of the device, where
            // no variant applies, so the alias of the app default is the unpatched color there.
            themeColors(indexOffset, colors, levels, "@color/$aliasName")
                .forEach { (index, color) -> addTheme(index, color) }
        }

        addThemes(
            THEME_INDEX_OFFSET_DARK, THEME_COLORS_DARK, PALETTE_LEVELS_DARK,
            THEME_COLOR_DARK
        )
        if (includeLightColor) {
            addThemes(
                THEME_INDEX_OFFSET_LIGHT, THEME_COLORS_LIGHT, PALETTE_LEVELS_LIGHT,
                THEME_COLOR_LIGHT
            )
        }
    }
}

/**
 * Declares the theme colors as overlayable, which an overlay the app registers for itself
 * requires. Without this the system rejects the overlay of a custom theme color.
 */
private fun ResourcePatchContext.declareOverlayableColors(colorNames: List<String>) {
    // A policy item is resolved while encoding, and every name is one of the app declares.
    if (colorNames.isEmpty()) {
        throw PatchException("Could not find any theme color to declare as overlayable")
    }

    val overlayable = buildString {
        appendLine("    <overlayable name=\"$THEME_COLOR_OVERLAYABLE_NAME\">")
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

/**
 * The color of every theme the extension can ask for, mapped to the index it asks with.
 * The color variants and the splash screen themes are both generated from this.
 *
 * @param appDefaultColor Color of the theme of the app itself, or null to leave it out
 *                        because it keeps the color the app declares.
 */
private fun themeColors(
    indexOffset: Int,
    colors: List<ThemeColor>,
    levels: IntArray,
    appDefaultColor: String? = null
): Map<Int, String> = buildMap {
    colors.forEachIndexed { index, themeColor ->
        // A color the user picks is not known while patching, and the palette below is used
        // for it instead.
        val color = themeColor.color ?: if (index == 0) appDefaultColor else null

        // The configuration value of a theme is its index plus one,
        // and the extension uses the same numbering.
        if (color != null) {
            put(indexOffset + index + 1, color)
        }
    }

    for (index in 0 until 512) {
        put(indexOffset + PALETTE_INDEX_OFFSET + index, paletteColor(levels, index))
    }
}

/**
 * @param aliasAlphas The alpha channel of each alias color.
 */
private fun ResourcePatchContext.addColorVariants(
    indexOffset: Int,
    colors: List<ThemeColor>,
    levels: IntArray,
    aliasAlphas: Map<String, Int>,
    isDark: Boolean
) {
    // The app default is the only color that keeps the colors the app declares,
    // so it is the only variant that has to undo the alias.
    val originalColors = LinkedHashMap<String, String>()
    document("res/values/colors.xml").use { document ->
        val colorNames = if (isDark) darkColorNames else lightColorNames
        document.getNode("resources").forEachChildElement { color ->
            val name = color.getAttribute("name")
            if (name in colorNames) {
                originalColors[name] = color.textContent
            }
        }
    }
    writeColorVariant(indexOffset + 1, originalColors, isDark)

    themeColors(indexOffset, colors, levels).forEach { (index, color) ->
        val mappedColors = aliasAlphas.mapValues { (_, alpha) -> applyAlpha(color, alpha) }
        writeColorVariant(index, mappedColors, isDark)
    }
}

/**
 * Gives every theme color of the app the value of a single color, which the generated
 * variants then declare instead of every name.
 *
 * The app keeps resolving the names it always did, including the ones its own code reads by id,
 * because only the value of a name is replaced and not the name itself.
 *
 * @param colorFiles             All color resource files.
 * @param declaredColors         The names and values of every color the app declares.
 * @param includeLightColor      If the light theme has its own theme color.
 * @return The alpha channel of each alias color.
 */
private fun ResourcePatchContext.addColorAliases(
    colorFiles: List<String>,
    declaredColors: Map<String, String>,
    includeLightColor: Boolean
): Map<String, Int> {
    val aliasAlphas = LinkedHashMap<String, Int>()

    colorFiles.forEach { path ->
        document(path).use { document ->
            val resources = document.getNode("resources")

            resources.forEachChildElement { color ->
                val name = color.getAttribute("name")
                val aliasBaseName = when (name) {
                    in darkColorNames -> THEME_COLOR_DARK
                    in lightColorNames -> if (includeLightColor) THEME_COLOR_LIGHT else null
                    else -> null
                } ?: return@forEachChildElement

                val alpha = parseAlpha(resolveColorValue(color.textContent, declaredColors))
                val colorAlias = if (alpha == 0xFF) {
                    aliasBaseName
                } else {
                    "${aliasBaseName}_opacity_${"%02X".format(alpha)}"
                }

                aliasAlphas[colorAlias] = alpha
                color.textContent = "@color/$colorAlias"
            }
        }
    }

    // Without a variant the alias resolves to the color of the unpatched app, which is
    // what the system draws the splash screen of the app default with.
    document("res/values/colors.xml").use { document ->
        val resources = document.getNode("resources")

        aliasAlphas.forEach { (name, alpha) ->
            resources.appendChild(
                document.createElement("color").apply {
                    setAttribute("name", name)

                    // The color is the unpatched color of the app.
                    val originalColor = if (isDarkThemeColorAlias(name)) {
                        DEFAULT_THEME_COLOR_DARK
                    } else {
                        DEFAULT_THEME_COLOR_LIGHT
                    }
                    textContent = applyAlpha(originalColor, alpha)
                }
            )
        }
    }

    return aliasAlphas
}

private fun isDarkThemeColorAlias(aliasName: String) =
    aliasName.startsWith(THEME_COLOR_DARK)

/**
 * @param color #AARRGGBB, #RRGGBB, or an Android color resource reference.
 * @return The alpha channel of the color (0-255).
 */
private fun parseAlpha(color: String): Int {
    if (color.startsWith("#")) {
        val hex = color.substring(1)
        if (hex.length == 8) {
            return hex.substring(0, 2).toInt(16)
        }
    }
    return 0xFF
}

/**
 * Combines a color with an alpha channel.
 */
private fun applyAlpha(color: String, alpha: Int): String {
    if (alpha == 0xFF || !color.startsWith("#")) {
        return color
    }

    val hex = color.substring(1)
    return "#%02X%s".format(alpha, if (hex.length == 8) hex.substring(2) else hex)
}

/**
 * The color of a value of the 9 bit palette, which the extension picks the index of.
 */
private fun paletteColor(levels: IntArray, index: Int) = "#%02X%02X%02X".format(
    levels[(index shr 6) and 0x7],
    levels[(index shr 3) and 0x7],
    levels[index and 0x7]
)

private fun ResourcePatchContext.writeColorVariant(
    index: Int,
    colors: Map<String, String>,
    isDark: Boolean
) {
    // The mobile code of a variant is never one a device can have, so the resource system uses
    // a variant only when the app asks for it. The extension uses the same encoding.
    val code = UNREACHABLE_MOBILE_CODE +
            (index - if (isDark) THEME_INDEX_OFFSET_DARK else THEME_INDEX_OFFSET_LIGHT)
    val qualifier = if (isDark) "mcc$code" else "mnc$code"

    val variantDirectory = get("res").resolve("values-$qualifier")
    variantDirectory.mkdirs()

    variantDirectory.resolve("colors.xml").writeText(
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<resources>")
            colors.forEach { (name, color) ->
                appendLine("    <color name=\"$name\">$color</color>")
            }
            appendLine("</resources>")
        }
    )
}
