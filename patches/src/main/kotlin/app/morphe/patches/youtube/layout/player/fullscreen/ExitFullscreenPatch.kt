package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.playerStatusMethod
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
internal val exitFullscreenPatch = bytecodePatch(
    name = "Exit fullscreen mode",
    description = "Adds options to automatically exit fullscreen mode when a video reaches the end."
) {

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        playerControlsPatch,
        videoInformationPatch
    )

    // Cannot declare as top level since this patch is in the same package as
    // other patches that declare same constant name with internal visibility.
    @Suppress("LocalVariableName")
    val EXTENSION_CLASS_DESCRIPTOR =
        "Lapp/morphe/extension/youtube/patches/ExitFullscreenPatch;"

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_exit_fullscreen")
        )

        playerStatusMethod.apply {
            val insertIndex =
                indexOfFirstInstructionOrThrow(Opcode.SGET_OBJECT) + 1

            addInstruction(
                insertIndex,
                "invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->endOfVideoReached(Ljava/lang/Enum;)V",
            )
        }
    }
}
