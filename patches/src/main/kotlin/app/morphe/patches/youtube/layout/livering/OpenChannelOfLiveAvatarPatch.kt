package app.morphe.patches.youtube.layout.livering

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.shortsplayer.ShortsPlaybackIntentFingerprint
import app.morphe.patches.youtube.layout.shortsplayer.ShortsPlaybackIntentFingerprintLegacy
import app.morphe.patches.youtube.misc.playservice.is_21_20_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
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
        addResourcesPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_open_channel_of_live_avatar", summary = true)
        )

        // Activity is used as the context to launch an Intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethod = PlaybackStartDescriptorToStringFingerprint
            .instructionMatches[1].getMethodCalled()
        fun patchLogic(mapRegister: String, playerDescriptorClassRegister: String, free1: String, free2: String): String {
            val methodParameter = playerDescriptorClassRegister.startsWith("p")

            return """
                move-object/from16 $free1, $mapRegister
                ${
                    if (methodParameter) "move-object/from16 $free2, $playerDescriptorClassRegister"
                    else ""
                }
                invoke-virtual { ${
                    if (methodParameter) free2
                    else playerDescriptorClassRegister
                } }, ${playbackStartVideoIdMethod.definingClass}->${playbackStartVideoIdMethod.name}()Ljava/lang/String;
                move-result-object $free2
                invoke-static { $free1, $free2 }, $EXTENSION_CLASS->openChannel(Ljava/util/Map;Ljava/lang/String;)Z
                move-result $free1
                if-eqz $free1, :ignore
                return-void
                :ignore
                nop
            """
        }

        clientSettingEndpointFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches[1]
                var moveResultRegister = match.getInstruction<OneRegisterInstruction>().registerA
                val insertIndex = match.index + 1
                val registerProvider = getFreeRegisterProvider(insertIndex, 2, moveResultRegister)
                var free1 = registerProvider.getFreeRegister()
                var free2 = registerProvider.getFreeRegister()

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    patchLogic("p2", "v$moveResultRegister", "v$free1", "v$free2")
                )
            }
        }

        // Same method is modified by openShortsInRegularPlayerPatch,
        // and by coincidence that patch runs before this patch which is critical.
        (if (is_21_20_or_greater) ShortsPlaybackIntentFingerprint
        else ShortsPlaybackIntentFingerprintLegacy).method.addInstructionsWithLabels(
            0,
            patchLogic("p2", "p1", "v0", "v1")
        )
    }
}
