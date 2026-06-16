package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.playerStatusMethodRef
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

@Suppress("unused")
val openVideosFullscreenPatch = bytecodePatch(
    name = "Open videos fullscreen",
    description = "Adds options to automatically open videos in fullscreen portrait or landscape mode."
) {
    dependsOn(
        openVideosFullscreenHookPatch,
        settingsPatch,
        videoInformationPatch,
        playerTypeHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_open_videos_fullscreen")
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        onCreateHook(EXTENSION_CLASS, "initialize")

        playerStatusMethodRef.get()!!.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.SGET_OBJECT) + 1
            addInstruction(
                insertIndex,
                "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->playerStatusChanged(Ljava/lang/Enum;)V"
            )
        }
    }
}
