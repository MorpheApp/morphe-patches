package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.layout.theme.THEME_COLOR_OPTION_DESCRIPTION
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.layout.theme.darkThemeBackgroundColorOption
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.overrideThemeColors
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.seekbar.seekbarColorPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_47_or_greater
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.forEachChildElement
import app.morphe.util.insertLiteralOverride
import org.w3c.dom.Element

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/theme/ThemePatch;"

val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS_DESCRIPTOR,

    block = {
        val lightThemeBackgroundColor by stringOption(
            key = "lightThemeBackgroundColor",
            default = "@android:color/white",
            values =  mapOf(
                "White" to "@android:color/white",
                "Material You" to "@android:color/system_neutral1_50",
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

        val themeResourcePatch = resourcePatch {
            dependsOn(resourceMappingPatch)

            execute {
                overrideThemeColors(
                    lightThemeBackgroundColor!!,
                    darkThemeBackgroundColorOption.value!!
                )

                fun addColorResource(
                    resourceFile: String,
                    colorName: String,
                    colorValue: String,
                ) {
                    document(resourceFile).use { document ->
                        val resourcesNode =
                            document.getElementsByTagName("resources").item(0) as Element

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
                    lightThemeBackgroundColor!!
                )
                addColorResource(
                    "res/values-night/colors.xml",
                    splashBackgroundColorKey,
                    darkThemeBackgroundColorOption.value!!
                )

                // Edit splash screen files and change the background color.
                arrayOf(
                    "res/drawable/quantum_launchscreen_youtube.xml",
                    "res/drawable-sw600dp/quantum_launchscreen_youtube.xml",
                ).forEach editSplashScreen@{ resourceFileName ->
                    document(resourceFileName).use { document ->
                        document.getElementsByTagName(
                            "layer-list"
                        ).item(0).forEachChildElement { node ->
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

                    val resourcesNode =
                        document.getElementsByTagName("resources").item(0) as Element
                    resourcesNode.appendChild(style)
                }
            }
        }
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            seekbarColorPatch,
            baseThemeResourcePatch(
                lightColorReplacement = { lightThemeBackgroundColor!! }
            ),
            themeResourcePatch
        )

        compatibleWith(
            "com.google.android.youtube"(
                "19.43.41",
                "20.14.43",
                "20.21.37",
                "20.31.42",
                "20.46.41",
            )
        )
    },

    executeBlock = {

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            SwitchPreference("morphe_gradient_loading_screen")
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
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = preferences
            )
        )

        if (is_19_47_or_greater) {
            PreferenceScreen.GENERAL_LAYOUT.addPreferences(
                ListPreference("morphe_splash_screen_animation_style")
            )
        }

        UseGradientLoadingScreenFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS_DESCRIPTOR->gradientLoadingScreenEnabled(Z)Z"
            )
        }

        if (is_19_47_or_greater) {
            // Lottie splash screen exists in earlier versions, but it may not be always on.
            SplashScreenStyleFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS_DESCRIPTOR->getLoadingScreenType(I)I"
                )
            }
        }
    }
)
