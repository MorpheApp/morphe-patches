package app.morphe.patches.music.layout.theme

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_DARK_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.layout.theme.darkThemeBackgroundColorOption
import app.morphe.patches.shared.misc.settings.overrideThemeColors

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/theme/ThemePatch;"

@Suppress("unused")
val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS_DESCRIPTOR,

    block = {
        dependsOn(
            sharedExtensionPatch,
            baseThemeResourcePatch(
                darkColorNames = THEME_DEFAULT_DARK_COLOR_NAMES + setOf(
                    "yt_black_pure",
                    "yt_black_pure_opacity80",
                    "ytm_color_grey_12",
                    "material_grey_800"
                )
            )
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.37.56",
            )
        )
    },

    executeBlock = {
        overrideThemeColors(
            null,
            darkThemeBackgroundColorOption.value!!
        )
    }
)
