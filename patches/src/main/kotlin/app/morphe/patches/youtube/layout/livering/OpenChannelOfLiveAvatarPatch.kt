package app.morphe.patches.youtube.layout.livering

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.localesYouTube
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.shortsplayer.ShortsPlaybackIntentFingerprint
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyXmlNode
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.inputStreamFromBundledResource
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val openChannelOfLiveAvatarResourcePatch = resourcePatch(
    description = "openChannelOfLiveAvatarResourcePatch"
) {
    execute {
        localesYouTube.filter { it.isBuiltInLanguage }.forEach { locale ->
            val directory = locale.getDestLocaleFolderName()
            val targetResource = "$directory/strings.xml"

            inputStreamFromBundledResource(
                "livering/host",
                targetResource
            )!!.let { inputStream ->
                "resources".copyXmlNode(
                    document(inputStream),
                    document("res/$targetResource")
                ).close()
            }
        }
    }
}

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
        openChannelOfLiveAvatarResourcePatch,
        versionCheckPatch,
    )

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_open_channel_of_live_avatar")
        )

        // Activity is used as the context to launch an Intent.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        val playbackStartVideoIdMethod = PlaybackStartDescriptorToStringFingerprint
            .instructionMatches[1].getMethodCalled()
        val playbackStartVideoIdMethodName = playbackStartVideoIdMethod.name
        val playbackStartVideoIdMethodClass = playbackStartVideoIdMethod.definingClass

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
                    """
                        move-object/from16 v$free1, p2
                        invoke-virtual { v$moveResultRegister }, $playbackStartVideoIdMethodClass->$playbackStartVideoIdMethodName()Ljava/lang/String;
                        move-result-object v$free2
                        invoke-static { v$free1, v$free2 }, $EXTENSION_CLASS->openChannel(Ljava/util/Map;Ljava/lang/String;)Z
                        move-result v$free1
                        if-eqz v$free1, :ignore
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }

        // Same method is modified by openShortsInRegularPlayerPatch,
        // and by coincidence that patch runs before this patch which is critical.
        ShortsPlaybackIntentFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                invoke-virtual { v0 }, $playbackStartVideoIdMethodClass->$playbackStartVideoIdMethodName()Ljava/lang/String;
                move-result-object v1
                move-object/from16 v0, p2
                invoke-static { v0, v1 }, $EXTENSION_CLASS->openChannel(Ljava/util/Map;Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :ignore
                return-void
                :ignore
                nop
            """
        )
    }
}
