/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.layout.theme.DEFAULT_DARK_THEME_BACKGROUND_COLOR
import app.morphe.patches.shared.layout.theme.DEFAULT_LIGHT_THEME_BACKGROUND_COLOR
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_DARK_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_LIGHT_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.layout.seekbar.seekbarColorPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_06_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_08_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.forEachChildElement
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/theme/ThemePatch;"

private val darkColorNames = {
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
}

private val lightColorNames = {
    THEME_DEFAULT_LIGHT_COLOR_NAMES + if (is_21_06_or_greater)
        setOf(
            "yt_sys_color_baseline_light_base_background",
            "yt_sys_color_baseline_light_raised_background"
        )
    else emptySet()
}

val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS,
    includeLightBackground = true,
    darkColorNames = darkColorNames,
    lightColorNames = lightColorNames,
    useModernLithoColorHook = {
        is_21_30_or_greater
    },
    block = {
        val themeResourcePatch = resourcePatch {
            dependsOn(resourceMappingPatch)

            execute {
                val lightThemeBackgroundColor = DEFAULT_LIGHT_THEME_BACKGROUND_COLOR
                val darkThemeBackgroundColor = DEFAULT_DARK_THEME_BACKGROUND_COLOR

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
            }
        }

        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            resourceMappingPatch,
            seekbarColorPatch,
            versionCheckPatch,
            baseThemeResourcePatch(
                includeLightBackground = true,
                darkColorNames = darkColorNames,
                lightColorNames = lightColorNames
            ),
            themeResourcePatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },

    executeBlock = {
        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    "morphe_theme_background_dark",
                    tag = "app.morphe.extension.shared.theme.ThemeBackgroundListPreference"
                ),
                TextPreference(
                    "morphe_theme_background_dark_custom_color",
                    tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                    inputType = InputType.TEXT_CAP_CHARACTERS
                ),
                ListPreference(
                    "morphe_theme_background_light",
                    tag = "app.morphe.extension.shared.theme.ThemeBackgroundListPreference"
                ),
                TextPreference(
                    "morphe_theme_background_light_custom_color",
                    tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                    inputType = InputType.TEXT_CAP_CHARACTERS
                )
            ),
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

        // Color of the new content indicator of the pivot bar, which is red in the app and does
        // not go with a Material You background.
        PivotBarNewContentDotFingerprint.let {
            it.method.apply {
                // Both the dot of a tab and the count next to it, and the count is hooked
                // first so the index of the dot is still valid.
                val countIndex = indexOfFirstInstructionOrThrow(it.instructionMatches[3].index) {
                    opcode == Opcode.CHECK_CAST
                }

                arrayOf(countIndex, it.instructionMatches[2].index).forEach { checkCastIndex ->
                    val stubRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                    addInstruction(
                        checkCastIndex + 1,
                        "invoke-static { v$stubRegister }, $EXTENSION_CLASS" +
                                "->onNewContentIndicator(Landroid/view/ViewStub;)V"
                    )
                }
            }
        }

        UseGradientLoadingScreenFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->gradientLoadingScreenEnabled(Z)Z"
            )
        }

        if (is_21_08_or_greater) {
            CarbonColorThemeFeatureFlagFingerprint.matchAll().forEach {
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

        ShowSplashScreenFingerprint.let {
            it.method.apply {
                val lastIndex = it.instructionMatches.last().index
                val lastInstruction = getInstruction<TwoRegisterInstruction>(lastIndex)
                val lastRegisterA = lastInstruction.registerA
                val lastRegisterB = lastInstruction.registerB

                addInstructions(
                    lastIndex,
                    """
                        invoke-static { v$lastRegisterA, v$lastRegisterB }, $EXTENSION_CLASS->showSplashScreen(II)I
                        move-result v$lastRegisterA
                    """
                )

                val firstIndex = it.instructionMatches[1].index
                val firstRegister = getInstruction<OneRegisterInstruction>(
                    firstIndex
                ).registerA

                addInstructions(
                    firstIndex + 1,
                    """
                        invoke-static { v$firstRegister }, $EXTENSION_CLASS->showSplashScreen(Z)Z
                        move-result v$firstRegister
                    """
                )
            }
        }
    }
)
