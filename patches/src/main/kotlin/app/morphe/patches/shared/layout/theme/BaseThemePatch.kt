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
 * above 999. Every generated variant uses a code above it, which is the only way the system can be
 * kept from using a variant of its own accord. Anything the system draws, such as the splash
 * screen, is resolved with the configuration of the device and not with the one the app asks for.
 */
private const val UNREACHABLE_MOBILE_CODE = 1000

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
 * The color of the background of each theme, which every background color of the app is an alias
 * of. Only this one color is declared by a resource variant, so a variant holds a single entry
 * instead of one for every color the app uses for its background.
 */
private const val THEME_BACKGROUND_COLOR_DARK = "morphe_theme_background_color_dark"
private const val THEME_BACKGROUND_COLOR_LIGHT = "morphe_theme_background_color_light"

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
 * A background that can be selected in the app settings.
 *
 * @param value Name of the matching value of the extension enum `ThemeColorPatch.ThemeColorDark`.
 * @param color The color, or null for the unpatched color of the app, which has no variant.
 */
private class ThemeBackground(val value: String, val color: String?)

/**
 * Backgrounds that can be selected in the app settings.
 *
 * The position of a background is the ordinal of the matching value of the extension enum, and
 * the extension selects the color of a position using the 'mcc' resource qualifier. The order of
 * this list and of that enum must always be the same, which the Theme patch verifies.
 */
private val THEME_BACKGROUNDS_DARK = listOf(
    ThemeBackground("APP_DEFAULT", null),
    ThemeBackground("PURE_BLACK", "@android:color/black"),
    ThemeBackground("MATERIAL_YOU_PURE_BLACK", "@android:color/black"),
    ThemeBackground("MATERIAL_YOU_NEUTRAL", "@android:color/system_neutral1_900"),
    ThemeBackground("MATERIAL_YOU_PRIMARY", "@android:color/system_accent1_800"),
    ThemeBackground("MATERIAL_YOU_SECONDARY", "@android:color/system_accent2_800"),
    ThemeBackground("MATERIAL_YOU_TERTIARY", "@android:color/system_accent3_800"),
    ThemeBackground("CLASSIC_YOUTUBE", "#212121"),
    ThemeBackground("CATPPUCCIN_MOCHA", "#181825"),
    ThemeBackground("DARK_PINK", "#290025"),
    ThemeBackground("DARK_BLUE", "#001029"),
    ThemeBackground("DARK_GREEN", "#002905"),
    ThemeBackground("DARK_YELLOW", "#282900"),
    ThemeBackground("DARK_ORANGE", "#291800"),
    ThemeBackground("DARK_RED", "#290000"),
    ThemeBackground("CUSTOM", null),
)

/**
 * Selected using the 'mnc' resource qualifier.
 *
 * @see THEME_BACKGROUNDS_DARK
 */
private val THEME_BACKGROUNDS_LIGHT = listOf(
    ThemeBackground("APP_DEFAULT", null),
    ThemeBackground("WHITE", "@android:color/white"),
    ThemeBackground("MATERIAL_YOU_WHITE", "@android:color/white"),
    ThemeBackground("MATERIAL_YOU_NEUTRAL", "@android:color/system_neutral1_100"),
    ThemeBackground("MATERIAL_YOU_PRIMARY", "@android:color/system_accent1_200"),
    ThemeBackground("MATERIAL_YOU_SECONDARY", "@android:color/system_accent2_200"),
    ThemeBackground("MATERIAL_YOU_TERTIARY", "@android:color/system_accent3_200"),
    ThemeBackground("CATPPUCCIN_LATTE", "#E6E9EF"),
    ThemeBackground("LIGHT_PINK", "#FCCFF3"),
    ThemeBackground("LIGHT_BLUE", "#D1E0FF"),
    ThemeBackground("LIGHT_GREEN", "#CCFFCC"),
    ThemeBackground("LIGHT_YELLOW", "#FDFFCC"),
    ThemeBackground("LIGHT_ORANGE", "#FFE6CC"),
    ThemeBackground("LIGHT_RED", "#FFD6D6"),
    ThemeBackground("CUSTOM", null),
)

/**
 * The splash screen is drawn by the system before the app can select a background,
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
        "removes the background color setting from the app."

/**
 * Dark theme background color of the YouTube and YT Music Theme patch.
 *
 * A color that is set here is what a user of an old device needs: the splash screen the system
 * draws uses it on every Android version, and nothing is generated for the backgrounds that
 * could otherwise be selected in the app settings.
 */
internal val darkThemeBackgroundColorOption = colorOption(
    key = "darkThemeBackgroundColor",
    default = THEME_COLOR_IN_APP,
    values = mapOf(
        "Change in the app" to THEME_COLOR_IN_APP,
        "Pure black" to "@android:color/black",
        "Material You (Neutral)" to "@android:color/system_neutral1_900",
        "Material You - Primary" to "@android:color/system_accent1_800",
        "Material You - Secondary" to "@android:color/system_accent2_800",
        "Material You - Tertiary" to "@android:color/system_accent3_800",
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
    title = "Dark theme background color",
    description = THEME_COLOR_OPTION_DESCRIPTION
)

/**
 * Light theme background color of the YouTube Theme patch.
 *
 * @see darkThemeBackgroundColorOption
 */
internal val lightThemeBackgroundColorOption = colorOption(
    key = "lightThemeBackgroundColor",
    default = THEME_COLOR_IN_APP,
    values = mapOf(
        "Change in the app" to THEME_COLOR_IN_APP,
        "White" to "@android:color/white",
        "Material You (Neutral)" to "@android:color/system_neutral1_100",
        "Material You - Primary" to "@android:color/system_accent1_200",
        "Material You - Secondary" to "@android:color/system_accent2_200",
        "Material You - Tertiary" to "@android:color/system_accent3_200",
        "Catppuccin (Latte)" to "#E6E9EF",
        "Light pink" to "#FCCFF3",
        "Light blue" to "#D1E0FF",
        "Light green" to "#CCFFCC",
        "Light yellow" to "#FDFFCC",
        "Light orange" to "#FFE6CC",
        "Light red" to "#FFD6D6",
    ),
    title = "Light theme background color",
    description = THEME_COLOR_OPTION_DESCRIPTION
)

/**
 * Setting the color of one theme of an app that has two applies both, otherwise one theme could
 * still be changed while the app runs and the other one not.
 */
internal val usePatchedBackgroundColor: Boolean
    get() = darkThemeBackgroundColorOption.value != THEME_COLOR_IN_APP ||
            lightThemeBackgroundColorOption.value != THEME_COLOR_IN_APP

internal val patchedBackgroundColorDark: String
    get() = patchedBackgroundColor(
        darkThemeBackgroundColorOption.value, appBackgroundColorDark, DEFAULT_THEME_COLOR_DARK
    )

internal val patchedBackgroundColorLight: String
    get() = patchedBackgroundColor(
        lightThemeBackgroundColorOption.value, appBackgroundColorLight, DEFAULT_THEME_COLOR_LIGHT
    )

/**
 * @param appColor The color the app uses for the background of this theme, which the resource
 *                 patch fills in.
 * @param default  The value the app setting defaults to, which the splash screen has to use
 *                 while the background can still be changed in the app.
 */
private fun patchedBackgroundColor(value: String?, appColor: String?, default: String) = when {
    value != null && value != THEME_COLOR_IN_APP -> value
    // A theme that is left to the app keeps the color of the app, so that applying the color of
    // one theme does not change the other one as well.
    usePatchedBackgroundColor -> appColor ?: default
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

/**
 * The color the unpatched app uses for the background of each theme, filled in with the names.
 */
private var appBackgroundColorDark: String? = null
private var appBackgroundColorLight: String? = null

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
    darkThemeBackgroundColorOption()

    if (includeLightBackground) {
        lightThemeBackgroundColorOption()
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

        if (usePatchedBackgroundColor) {
            overrideThemeColors(
                if (includeLightBackground) patchedBackgroundColorLight else null,
                patchedBackgroundColorDark
            )

            PatchedBackgroundColorDarkFingerprint.method.returnEarly(patchedBackgroundColorDark)
            if (includeLightBackground) {
                PatchedBackgroundColorLightFingerprint.method
                    .returnEarly(patchedBackgroundColorLight)
            }
        } else {
            verifyBackgrounds("ThemeColorDark", THEME_BACKGROUNDS_DARK)
            if (includeLightBackground) {
                verifyBackgrounds("ThemeColorLight", THEME_BACKGROUNDS_LIGHT)
            }

            // Morphe dialogs and settings use the background color of the app, and the color
            // resources resolve to the background that is selected in the app settings.
            overrideThemeColors(
                if (includeLightBackground) THEME_BACKGROUND_COLOR_LIGHT else null,
                THEME_BACKGROUND_COLOR_DARK
            )

            // A custom background color has no resource variant to select,
            // so the extension replaces the same colors with an overlay of the app.
            DarkColorResourceNamesFingerprint.method.returnEarly(THEME_BACKGROUND_COLOR_DARK)
            if (includeLightBackground) {
                LightColorResourceNamesFingerprint.method
                    .returnEarly(THEME_BACKGROUND_COLOR_LIGHT)
            }
        }

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

/**
 * Fails the patch if a background exists in the extension enum and not in the list of the patch,
 * or the other way around. A background is selected by its position in both, so one that is added
 * to only one of them silently shifts every background that follows it.
 */
private fun BytecodePatchContext.verifyBackgrounds(
    enumName: String,
    backgrounds: List<ThemeBackground>
) {
    val enumType = THEME_COLOR_EXTENSION_CLASS.dropLast(1) + '$' + enumName + ";"

    // A value of an enum is a static field of the type of the enum itself.
    val declared = classDefBy(enumType).fields
        .filter { it.type == enumType }
        .map { it.name }
        .toSet()

    val expected = backgrounds.mapTo(mutableSetOf()) { it.value }
    if (declared != expected) {
        throw PatchException(
            "Backgrounds of $enumName do not match the patch. " +
                    "Only in the patch: ${expected - declared}. " +
                    "Only in the extension: ${declared - expected}"
        )
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
    declaredColors: Map<String, String>
): List<String> {
    val appColorName = appColorNames.firstOrNull { it in declaredColors }
        ?: throw PatchException("Could not find the background color of the app: $appColorNames")

    return (listOf(appColorName) + colorNames).distinct().filter { it in declaredColors }
}

/**
 * Every color the app declares, mapped to its value.
 */
private fun ResourcePatchContext.declaredColors(): Map<String, String> {
    val declaredColors = LinkedHashMap<String, String>()

    document("res/values/colors.xml").use { document ->
        document.getNode("resources").forEachChildElement {
            declaredColors[it.getAttribute("name")] = it.textContent
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
        val declaredColors = declaredColors()
        darkColorNames = themeColorNames(APP_COLOR_NAMES_DARK, colorNamesDark(), declaredColors)
        lightColorNames = if (includeLightBackground) {
            themeColorNames(APP_COLOR_NAMES_LIGHT, colorNamesLight(), declaredColors)
        } else {
            emptyList()
        }

        appBackgroundColorDark = declaredColors[darkColorNames.first()]
        appBackgroundColorLight = lightColorNames.firstOrNull()?.let { declaredColors[it] }

        // A color that is set while patching is the only background the app can have,
        // so none of the variants and themes below are of any use.
        if (usePatchedBackgroundColor) {
            replaceBackgroundColors(includeLightBackground)
            return@execute
        }

        verifySettingEntries(
            "morphe_theme_background_dark", "values/shared-youtube/arrays.xml",
            THEME_BACKGROUNDS_DARK
        )
        addBackgroundColorVariants(
            THEME_INDEX_OFFSET_DARK, THEME_BACKGROUNDS_DARK, PALETTE_LEVELS_DARK,
            THEME_BACKGROUND_COLOR_DARK, darkColorNames, true
        )

        if (includeLightBackground) {
            verifySettingEntries(
                "morphe_theme_background_light", "values/youtube/arrays.xml",
                THEME_BACKGROUNDS_LIGHT
            )
            addBackgroundColorVariants(
                THEME_INDEX_OFFSET_LIGHT, THEME_BACKGROUNDS_LIGHT, PALETTE_LEVELS_LIGHT,
                THEME_BACKGROUND_COLOR_LIGHT, lightColorNames, false
            )
        }

        declareOverlayableColors(
            if (includeLightBackground) {
                listOf(THEME_BACKGROUND_COLOR_DARK, THEME_BACKGROUND_COLOR_LIGHT)
            } else {
                listOf(THEME_BACKGROUND_COLOR_DARK)
            }
        )

        // An app without a launcher theme keeps the splash screen it draws itself.
        if (splashScreenThemeParent != null) {
            addSplashScreenThemes(splashScreenThemeParent, includeLightBackground)
        }
    }
}

/**
 * Fails the patch if the setting entries of a background do not match the backgrounds of the
 * patch. The app settings show the color of a background by its position in both lists.
 */
private fun ResourcePatchContext.verifySettingEntries(
    key: String,
    resourcePath: String,
    backgrounds: List<ThemeBackground>
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

    val values = backgrounds.map { it.value }
    if (arrays["${key}_entry_values"] != values) {
        throw PatchException("The entry values of $key do not match the patch: $values")
    }

    // The name of a background is shown by the position it has in the values.
    if (arrays["${key}_entries"]?.size != values.size) {
        throw PatchException("The entries of $key do not match the entry values")
    }
}

/**
 * Gives the background colors of the app the color that is set as a patch option, which is what
 * the Theme patch did before the background could be changed in the app settings.
 */
private fun ResourcePatchContext.replaceBackgroundColors(includeLightBackground: Boolean) {
    val darkColor = patchedBackgroundColorDark
    if (!validateColorName(darkColor)) {
        throw PatchException("Invalid dark theme color: $darkColor")
    }

    val lightColor = patchedBackgroundColorLight
    if (includeLightBackground && !validateColorName(lightColor)) {
        throw PatchException("Invalid light theme color: $lightColor")
    }

    document("res/values/colors.xml").use { document ->
        document.getNode("resources").forEachChildElement { node ->
            when (node.getAttribute("name")) {
                in darkColorNames -> node.textContent = darkColor
                in lightColorNames -> node.textContent = lightColor
            }
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
            backgrounds: List<ThemeBackground>,
            levels: IntArray,
            aliasName: String
        ) {
            // The system resolves the splash screen with the configuration of the device, where
            // no variant applies, so the alias of the app default is the unpatched color there.
            themeColors(indexOffset, backgrounds, levels, "@color/$aliasName")
                .forEach { (index, color) -> addTheme(index, color) }
        }

        addThemes(
            THEME_INDEX_OFFSET_DARK, THEME_BACKGROUNDS_DARK, PALETTE_LEVELS_DARK,
            THEME_BACKGROUND_COLOR_DARK
        )
        if (includeLightBackground) {
            addThemes(
                THEME_INDEX_OFFSET_LIGHT, THEME_BACKGROUNDS_LIGHT, PALETTE_LEVELS_LIGHT,
                THEME_BACKGROUND_COLOR_LIGHT
            )
        }
    }
}

/**
 * Declares the background colors as overlayable, which an overlay the app registers for itself
 * requires. Without this the system rejects the overlay of a custom background color.
 */
private fun ResourcePatchContext.declareOverlayableColors(colorNames: List<String>) {
    // A policy item is resolved while encoding, and every name is one of the app declares.
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

/**
 * The color of every background the extension can ask for, mapped to the index it asks with.
 * The color variants and the splash screen themes are both generated from this.
 *
 * @param appDefaultColor Color of the background of the app itself, or null to leave it out
 *                        because it keeps the color the app declares.
 */
private fun themeColors(
    indexOffset: Int,
    backgrounds: List<ThemeBackground>,
    levels: IntArray,
    appDefaultColor: String? = null
): Map<Int, String> = buildMap {
    backgrounds.forEachIndexed { index, background ->
        // A color the user picks is not known while patching, and the palette below is used
        // for it instead.
        val color = background.color ?: if (index == 0) appDefaultColor else null

        // The configuration value of a background is its index plus one,
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
 * @param aliasName  The color every background color of the app is made an alias of.
 * @param colorNames The background colors of the app, which are aliased.
 */
private fun ResourcePatchContext.addBackgroundColorVariants(
    indexOffset: Int,
    backgrounds: List<ThemeBackground>,
    levels: IntArray,
    aliasName: String,
    colorNames: List<String>,
    isDark: Boolean
) {
    if (colorNames.isEmpty()) {
        throw PatchException("No color to replace for the app background")
    }

    val originalColors = addBackgroundColorAlias(aliasName, colorNames)

    // The app default is the only background that keeps the colors the app declares,
    // so it is the only variant that has to undo the alias.
    writeColorVariant(indexOffset + 1, originalColors, isDark)

    themeColors(indexOffset, backgrounds, levels).forEach { (index, color) ->
        writeColorVariant(index, mapOf(aliasName to color), isDark)
    }
}

/**
 * Gives every background color of the app the value of a single color, which the generated
 * variants then declare instead of every name.
 *
 * The app keeps resolving the names it always did, including the ones its own code reads by id,
 * because only the value of a name is replaced and not the name itself.
 *
 * @return The color each name had, so that the app default can be restored.
 */
private fun ResourcePatchContext.addBackgroundColorAlias(
    aliasName: String,
    colorNames: List<String>
): Map<String, String> {
    val originalColors = LinkedHashMap<String, String>()

    document("res/values/colors.xml").use { document ->
        val resources = document.getNode("resources")

        resources.forEachChildElement { color ->
            val name = color.getAttribute("name")
            if (name in colorNames) {
                originalColors[name] = color.textContent
                color.textContent = "@color/$aliasName"
            }
        }

        // Without a variant the alias resolves to the background of the unpatched app, which is
        // what the system draws the splash screen of the app default with.
        resources.appendChild(
            document.createElement("color").apply {
                setAttribute("name", aliasName)
                textContent = originalColors.getValue(colorNames.first())
            }
        )
    }

    return originalColors
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
