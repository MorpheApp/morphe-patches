package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_DARK_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_LIGHT_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.layout.theme.createNotifDrawable
import app.morphe.patches.shared.layout.theme.darkThemeBackgroundColorOption
import app.morphe.patches.shared.layout.theme.lightThemeBackgroundColorOption
import app.morphe.patches.shared.layout.theme.patchCountTextColor
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.seekbar.seekbarColorPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_06_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_08_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.forEachChildElement
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/theme/ThemePatch;"

val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS,
    includeLightThemeOption = true,
    block = {
        val themeResourcePatch = resourcePatch {
            lightThemeBackgroundColorOption()
            darkThemeBackgroundColorOption()
            dependsOn(resourceMappingPatch)

            execute {
                val lightThemeBackgroundColor = lightThemeBackgroundColorOption.value!!
                val darkThemeBackgroundColor = darkThemeBackgroundColorOption.value!!

                fun addColorResource(
                    resourceFile: String,
                    colorName: String,
                    colorValue: String,
                ) {
                    document(resourceFile).use { document ->
                        val resourcesNode = document.getElementsByTagName("resources").item(0) as Element

                        resourcesNode.appendChild(
                            document.createElement("color").apply {
                                setAttribute("name", colorName)
                                setAttribute("category", "color")
                                textContent = colorValue
                            }
                        )
                    }
                }

                // Add a dynamic background color to the colors.xml file.
                val splashBackgroundColorKey = "morphe_splash_background_color"
                addColorResource(
                    "res/values/colors.xml",
                    splashBackgroundColorKey,
                    lightThemeBackgroundColor
                )
                addColorResource(
                    "res/values-night/colors.xml",
                    splashBackgroundColorKey,
                    darkThemeBackgroundColor
                )

                // Edit splash screen files and change the background color.
                arrayOf(
                    "res/drawable/quantum_launchscreen_youtube.xml",
                    "res/drawable-sw600dp/quantum_launchscreen_youtube.xml",
                ).forEach editSplashScreen@{ resourceFileName ->
                    document(resourceFileName).use { document ->
                        document.getElementsByTagName("layer-list").item(0).forEachChildElement { node ->
                            if (node.hasAttribute("android:drawable")) {
                                node.setAttribute(
                                    "android:drawable",
                                    "@color/$splashBackgroundColorKey"
                                )
                                return@editSplashScreen
                            }
                        }

                        throw PatchException("Failed to modify launch screen")
                    }
                }

                // Fix the splash screen dark mode background color.
                // In 19.32+ the dark mode splash screen is white and fades to black.
                document("res/values-night/styles.xml").use { document ->
                    // Create a night mode specific override for the splash screen background.
                    val style = document.createElement("style")
                    style.setAttribute("name", "Theme.YouTube.Home")
                    style.setAttribute("parent", "@style/Base.V27.Theme.YouTube.Home")

                    // Fix status and navigation bar showing white on some Android devices,
                    // such as SDK 28 Android 10 medium tablet.
                    val colorSplashBackgroundColor = "@color/$splashBackgroundColorKey"
                    arrayOf(
                        "android:navigationBarColor" to colorSplashBackgroundColor,
                        "android:windowBackground" to colorSplashBackgroundColor,
                        "android:colorBackground" to colorSplashBackgroundColor,
                        "colorPrimaryDark" to colorSplashBackgroundColor,
                        "android:windowLightStatusBar" to "false",
                    ).forEach { (name, value) ->
                        val styleItem = document.createElement("item")
                        styleItem.setAttribute("name", name)
                        styleItem.textContent = value
                        style.appendChild(styleItem)
                    }

                    val resourcesNode = document.getElementsByTagName("resources").item(0) as Element
                    resourcesNode.appendChild(style)
                }

                arrayOf(
                    "res/values/styles.xml",
                    "res/values-v27/styles.xml",
                    "res/values-v31/styles.xml"
                ).forEach { stylesPath ->
                    try {
                        document(stylesPath).use { document ->
                            val resourcesNode = document.getElementsByTagName("resources").item(0) as? Element ?: return@use
                            var themeNode: Element? = null

                            resourcesNode.forEachChildElement { node ->
                                if (node.nodeName == "style" && node.getAttribute("name") == "Theme.YouTube.Home") {
                                    themeNode = node
                                }
                            }

                            if (themeNode == null) {
                                themeNode = document.createElement("style").apply {
                                    setAttribute("name", "Theme.YouTube.Home")
                                    setAttribute("parent", "@style/Base.V27.Theme.YouTube.Home")
                                    resourcesNode.appendChild(this)
                                }
                            }

                            var hasLightStatusBar = false
                            themeNode!!.forEachChildElement { node ->
                                if (node.nodeName == "item" && node.getAttribute("name") == "android:windowLightStatusBar") {
                                    node.textContent = "true"
                                    hasLightStatusBar = true
                                }
                            }

                            if (!hasLightStatusBar) {
                                val styleItem = document.createElement("item")
                                styleItem.setAttribute("name", "android:windowLightStatusBar")
                                styleItem.textContent = "true"
                                themeNode.appendChild(styleItem)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                val isMaterialYouLight = lightThemeBackgroundColor.startsWith("@android:color/system_")

                if (isMaterialYouLight) {
                    val resDir = get("res")
                    val lightDotColor = "@android:color/system_accent1_200"
                    val lightCountBgColor = "@android:color/system_accent1_100"
                    val lightCountTextColor = "@android:color/system_neutral1_900"

                    createNotifDrawable(resDir, "drawable/morphe_notif_dot_light.xml", lightDotColor, "oval")
                    createNotifDrawable(resDir, "drawable/morphe_notif_count_light.xml", lightCountBgColor, "rectangle", hasCorners = true)
                    patchCountTextColor(resDir, lightCountTextColor)

                    val stylesFile = "res/values/styles.xml"
                    if (get(stylesFile).exists()) {
                        document(stylesFile).use { document ->
                            val resources = document.getElementsByTagName("resources").item(0) as? Element ?: return@use

                            resources.forEachChildElement { style ->
                                if (style.nodeName != "style") return@forEachChildElement

                                val overrides: Map<String, String> = when (style.getAttribute("name")) {
                                    "PivotBar.Default" -> mapOf(
                                        "dotBackground" to "@drawable/morphe_notif_dot_light",
                                        "countBackground" to "@drawable/morphe_notif_count_light"
                                    )
                                    "CairoLightThemeUpdates" -> mapOf(
                                        "ytRedIndicator" to lightDotColor
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

        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            seekbarColorPatch,
            versionCheckPatch,
            baseThemeResourcePatch(
                lightColorReplacement = { lightThemeBackgroundColorOption.value!! },
                darkColorNames = {
                    THEME_DEFAULT_DARK_COLOR_NAMES + if (is_21_06_or_greater)
                        setOf(
                            // yt_ref_color_constants_baseline_black_black0
                            // yt_ref_color_constants_baseline_black_black1
                            // yt_ref_color_constants_baseline_black_black3
                            "yt_sys_color_baseline_dark_menu_background",
                            "yt_sys_color_baseline_dark_static_black",
                            "yt_sys_color_baseline_dark_raised_background",
                            "yt_sys_color_baseline_dark_base_background",
                            "yt_sys_color_baseline_light_inverted_background",
                            "yt_sys_color_baseline_light_static_black"
                        ) else emptySet()
                },
                lightColorNames = {
                    THEME_DEFAULT_LIGHT_COLOR_NAMES + if (is_21_06_or_greater)
                        setOf(
                            "yt_sys_color_baseline_light_base_background",
                            "yt_sys_color_baseline_light_raised_background"
                        )
                    else emptySet()
                }
            ),
            themeResourcePatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },

    executeBlock = {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_gradient_loading_screen", summary = true)
        )

        val preferences = mutableSetOf(
            SwitchPreference("morphe_seekbar_custom_color"),
            TextPreference(
                "morphe_seekbar_custom_color_primary",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS
            ),
            TextPreference(
                "morphe_seekbar_custom_color_accent",
                tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                inputType = InputType.TEXT_CAP_CHARACTERS
            )
        )

        PreferenceScreen.SEEKBAR.addPreferences(
            noTitleUnsortedPreferenceCategory(preferences)
        )

        PreferenceScreen.GENERAL.addPreferences(
            ListPreference("morphe_splash_screen_animation_style")
        )

        UseGradientLoadingScreenFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->gradientLoadingScreenEnabled(Z)Z"
            )
        }

        if (is_21_08_or_greater) {
            CarbonColorThemeFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
            }
        }

        // Lottie splash screen exists in earlier versions, but it may not be always on.
        SplashScreenStyleFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->getLoadingScreenType(I)I"
            )
        }

        ShowSplashScreen1Fingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->showSplashScreen(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        ShowSplashScreen2Fingerprint.let {
            val insertIndex = it.instructionMatches[1].index
            it.method.apply {
                val insertInstruction = getInstruction<TwoRegisterInstruction>(insertIndex)
                val registerA = insertInstruction.registerA
                val registerB = insertInstruction.registerB

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$registerA, v$registerB }, $EXTENSION_CLASS->showSplashScreen(II)I
                        move-result v$registerA
                    """
                )
            }
        }
    }
)