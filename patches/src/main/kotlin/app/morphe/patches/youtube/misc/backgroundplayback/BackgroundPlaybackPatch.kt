package app.morphe.patches.youtube.misc.backgroundplayback

import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_34_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal var prefBackgroundAndOfflineCategoryId = -1L
    private set

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch;"

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        resourceMappingPatch,
        addResourcesPatch,
        sharedExtensionPatch,
        playerTypeHookPatch,
        videoInformationPatch,
        settingsPatch,
        versionCheckPatch
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

    execute {
        addResources("youtube", "misc.backgroundplayback.backgroundPlaybackPatch")

        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_shorts_disable_background_playback")
        )

        prefBackgroundAndOfflineCategoryId = getResourceId(
            ResourceType.STRING,
            "pref_background_and_offline_category"
        )

        arrayOf(
            backgroundPlaybackManagerFingerprint to "isBackgroundPlaybackAllowed",
            backgroundPlaybackManagerShortsFingerprint to "isBackgroundShortsPlaybackAllowed",
        ).forEach { (fingerprint, integrationsMethod) ->
            fingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->$integrationsMethod(Z)Z
                            move-result v$register 
                        """,
                    )
                }
            }
        }

        // Enable background playback option in YouTube settings
        backgroundPlaybackSettingsFingerprint.originalMethod.apply {
            val booleanCalls = instructions.withIndex().filter {
                it.value.getReference<MethodReference>()?.returnType == "Z"
            }

            val settingsBooleanIndex = booleanCalls.elementAt(1).index
            val settingsBooleanMethod by navigate(this).to(settingsBooleanIndex)

            settingsBooleanMethod.returnEarly(true)
        }

        // Force allowing background play for Shorts.
        shortsBackgroundPlaybackFeatureFlagFingerprint.method.returnEarly(true)

        // Force allowing background play for videos labeled for kids.
        kidsBackgroundPlaybackPolicyControllerFingerprint.method.returnEarly()

        // Fix PiP buttons not working after locking/unlocking device screen.
        if (is_19_34_or_greater) {
            pipInputConsumerFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
            }
        }
    }
}
