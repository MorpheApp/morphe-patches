package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.util.childElementsSequence
import app.morphe.util.forEachChildElement
import org.w3c.dom.Element
import java.io.File
import java.util.Locale

internal const val THEME_COLOR_OPTION_DESCRIPTION = "Can be a hex color (#RRGGBB) or a color resource reference."

internal val THEME_DEFAULT_DARK_COLOR_NAMES = setOf(
    "yt_black0", "yt_black1", "yt_black2", "yt_black3", "yt_black4",
    "yt_black1_opacity95", "yt_black1_opacity98",
    "yt_status_bar_background_dark", "material_grey_850",
    "yt_sys_color_baseline_mobile_dark_default_base_background",
    "yt_sys_color_baseline_mobile_dark_default_raised_background"
)

internal val THEME_DEFAULT_LIGHT_COLOR_NAMES = setOf(
    "yt_white1", "yt_white2", "yt_white3", "yt_white4",
    "yt_white1_opacity95", "yt_white1_opacity98",
    "yt_sys_color_baseline_mobile_light_default_base_background",
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
 * @param colorString #AARRGGBB #RRGGBB, or an Android color resource name.
 */
internal fun validateColorName(colorString: String): Boolean {
    if (colorString.startsWith("#")) {
        // #RRGGBB or #AARRGGBB
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
 * Dark theme color options for YouTube and YT Music Theme patch.
 */
internal val darkThemeBackgroundColorOption = stringOption(
    key = "darkThemeBackgroundColor",
    default = "@android:color/black",
    values = mapOf(
        "Pure black" to "@android:color/black",
        "Material You (Neutral)" to "@android:color/system_neutral1_900",
        "Material You - Primary" to "@android:color/system_accent1_800",
        "Material You - Secondary" to "@android:color/system_accent2_800",
        "Material You - Tertiary" to "@android:color/system_accent3_800",
        "Classic (old YouTube)" to "#212121",
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
 * Light theme color options for YouTube Theme patch.
 */
internal val lightThemeBackgroundColorOption = stringOption(
    key = "lightThemeBackgroundColor",
    default = "@android:color/white",
    values =  mapOf(
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
 * Shared theme patch for YouTube and YT Music.
 */
internal fun baseThemePatch(
    extensionClassDescriptor: String,
    includeLightThemeOption: Boolean = false,
    block: BytecodePatchBuilder.() -> Unit,
    executeBlock: BytecodePatchContext.() -> Unit = {}
) = bytecodePatch(
    name = "Theme",
    description = "Adds options for theming and applies a custom background theme " +
            "(dark background theme defaults to pure black).",
) {
    darkThemeBackgroundColorOption()

    if (includeLightThemeOption) {
        lightThemeBackgroundColorOption()
    }

    block()

    dependsOn(lithoColorHookPatch)

    execute {
        overrideThemeColors(
            if (includeLightThemeOption)
                lightThemeBackgroundColorOption.value!! else null,
            darkThemeBackgroundColorOption.value!!
        )

        executeBlock()

        lithoColorOverrideHook(extensionClassDescriptor, "getValue")
    }
}

internal fun baseThemeResourcePatch(
    darkColorNames: (() -> Set<String>) = { THEME_DEFAULT_DARK_COLOR_NAMES },
    lightColorNames: (() -> Set<String>) = { THEME_DEFAULT_LIGHT_COLOR_NAMES },
    lightColorReplacement: (() -> String)? = null
) = resourcePatch {
    darkThemeBackgroundColorOption()

    execute {
        // Patch validators don't work here for unknown reason.
        // This should be changed to a patch option validator.
        val darkThemeBackgroundColor = darkThemeBackgroundColorOption.value!!
        if (!validateColorName(darkThemeBackgroundColor)) {
            throw PatchException("Invalid dark theme color: $darkThemeBackgroundColor")
        }

        val lightThemeBackgroundColor = lightColorReplacement?.invoke()
        if (lightThemeBackgroundColor != null && !validateColorName(lightThemeBackgroundColor)) {
            throw PatchException("Invalid light theme color: $lightThemeBackgroundColor")
        }

        document("res/values/colors.xml").use { document ->
            val resourcesNode = document.getElementsByTagName("resources").item(0) as Element
            val resolvedDarkNames = darkColorNames()
            val resolvedLightNames = lightColorNames()

            resourcesNode.childElementsSequence().forEach { node ->
                val name = node.getAttribute("name")
                when {
                    name in resolvedDarkNames -> node.textContent = darkThemeBackgroundColor
                    lightThemeBackgroundColor != null && name in resolvedLightNames -> node.textContent = lightThemeBackgroundColor
                }
            }
        }

        val isMaterialYouDark = darkThemeBackgroundColor.startsWith("@android:color/system_")

        if (isMaterialYouDark) {
            val resDir = get("res")
            val darkDotColor = "@android:color/system_accent1_100"
            val darkCountBgColor = "@android:color/system_accent1_100"
            val darkCountTextColor = "@android:color/system_neutral1_900"

            createNotifDrawable(resDir, "drawable/morphe_notif_dot_dark.xml", darkDotColor, "oval")
            createNotifDrawable(resDir, "drawable/morphe_notif_count_dark.xml", darkCountBgColor, "rectangle", hasCorners = true)
            patchCountTextColor(resDir, darkCountTextColor)

            val ytmDrawables = listOf(
                "new_content_dot_background.xml",
                "new_content_dot_background_cairo.xml",
                "new_content_count_background.xml",
                "new_content_count_background_cairo.xml"
            )
            val ytmDrawableDirs = listOf("drawable", "drawable-anydpi-v26", "drawable-anydpi", "drawable-v24", "drawable-v31")

            ytmDrawables.forEach { fileName ->
                ytmDrawableDirs.forEach { dirName ->
                    val file = resDir.resolve("$dirName/$fileName")
                    if (file.exists()) {
                        val patchedXml = file.readText().replace(
                            Regex("""<solid\s+android:color="[^"]+""""),
                            """<solid android:color="$darkDotColor""""
                        )
                        file.writeText(patchedXml)
                    }
                }
            }

            val ytmLayoutDirs = listOf("layout", "layout-v26", "layout-v31")
            ytmLayoutDirs.forEach { dirName ->
                val file = resDir.resolve("$dirName/new_content_count.xml")
                if (file.exists()) {
                    val patchedXml = file.readText().replace(
                        Regex("""android:textColor="[^"]+""""),
                        """android:textColor="$darkCountTextColor""""
                    )
                    file.writeText(patchedXml)
                }
            }

            val stylesFile = "res/values/styles.xml"
            if (get(stylesFile).exists()) {
                document(stylesFile).use { document ->
                    val resources = document.getElementsByTagName("resources").item(0) as? Element ?: return@use

                    resources.forEachChildElement { style ->
                        if (style.nodeName != "style") return@forEachChildElement

                        val overrides: Map<String, String> = when (style.getAttribute("name")) {
                            "PivotBar.Dark" -> mapOf(
                                "dotBackground" to "@drawable/morphe_notif_dot_dark",
                                "countBackground" to "@drawable/morphe_notif_count_dark"
                            )
                            "CairoDarkThemeUpdates" -> mapOf(
                                "ytRedIndicator" to darkDotColor
                            )
                            else -> return@forEachChildElement
                        }

                        overrides.forEach { (attrName, attrValue) ->
                            var found = false
                            style.forEachChildElement { item ->
                                if (item.nodeName == "item" && item.getAttribute("name") == attrName) {
                                    item.textContent = attrValue
                                    found = true
                                }
                            }
                            if (!found) {
                                style.appendChild(document.createElement("item").apply {
                                    setAttribute("name", attrName)
                                    textContent = attrValue
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}