package app.morphe.patches.youtube.layout.livering

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenChannelOfLiveAvatarPatch;"

@Suppress("unused")
val openChannelOfLiveAvatarPatch = bytecodePatch(
    name = "Open channel of live avatar",
    description = "Adds an option to prevent a channel's current live video from opening when tapping its avatar."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_open_channel_of_live_avatar")
        )

        // Activity is used as the context to launch an Intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethod =
            PlaybackStartDescriptorToStringFingerprint.instructionMatches[1].getMethodCalled()
        val playbackStartVideoIdMethodName = playbackStartVideoIdMethod.name
        val playbackStartVideoIdMethodClass = playbackStartVideoIdMethod.definingClass

        clientSettingEndpointFingerprint.let {
            val descriptorMutatorStringIndex = it.instructionMatches[4].index
            var videoDescriptorMoveResultIndex = descriptorMutatorStringIndex - 1
            var videoDescriptorMoveResultRegister = it.method.getInstruction<OneRegisterInstruction>(videoDescriptorMoveResultIndex).registerA
            var firstFreeRegister = it.method.getInstruction<OneRegisterInstruction>(descriptorMutatorStringIndex).registerA
            var secondFreeRegister = firstFreeRegister + 1

            it.method.addInstructionsAtControlFlowLabel(
                descriptorMutatorStringIndex,
                """
                    move-object/from16 v$firstFreeRegister, p2
                    invoke-virtual { v$videoDescriptorMoveResultRegister }, $playbackStartVideoIdMethodClass->$playbackStartVideoIdMethodName()Ljava/lang/String;
                    move-result-object v$secondFreeRegister
                    invoke-static { v$firstFreeRegister, v$secondFreeRegister }, $EXTENSION_CLASS->openChannel(Ljava/util/Map;Ljava/lang/String;)Z
                    move-result v$firstFreeRegister
                    if-eqz v$firstFreeRegister, :ignore
                    return-void
                    :ignore
                    nop
                """
            )
        }
    }
}
