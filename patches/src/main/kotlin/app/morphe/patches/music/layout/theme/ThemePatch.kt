package app.morphe.patches.music.layout.theme

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_30_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_DARK_COLOR_NAMES
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/theme/ThemePatch;"

@Suppress("unused")
val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS,
    useModernLithoColorHook = {
        is_9_30_or_greater
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            versionCheckPatch,
            baseThemeResourcePatch(
                darkColorNames = {
                    THEME_DEFAULT_DARK_COLOR_NAMES + setOf(
                        "yt_black_pure",
                        "yt_black_pure_opacity80",
                        "ytm_color_grey_12",
                        "material_grey_800"
                    )
                }
            )
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    }
)
