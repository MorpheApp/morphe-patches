package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableDoubleTapActionsPatch;"

@Suppress("unused")
val disableDoubleTapActionsPatch = bytecodePatch(
    name = "Disable double tap actions",
    description = "Adds an option to disable player double tap gestures.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_chapter_skip_double_tap", summary = true),
        )

        doubleTapInfoGetSeekSourceFingerprint(
            SeekTypeEnumFingerprint.originalClassDef.type
        ).let { doubleTapFingerprint ->

            // Force isChapterSeek flag to false.
            doubleTapFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                    move-result p1
                """
            )

            DoubleTapInfoCtorFingerprint.match(
                doubleTapFingerprint.classDef
            ).method.addInstructions(
                0,
                """
                    invoke-static { p3 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                    move-result p3
                """
            )
        }
    }
}
