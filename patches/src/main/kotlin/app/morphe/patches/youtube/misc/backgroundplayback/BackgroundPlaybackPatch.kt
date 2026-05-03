package app.morphe.patches.youtube.misc.backgroundplayback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.fix.bitmap.fixRecycledBitmapPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_29_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BackgroundPlaybackManagerShortsFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/BackgroundPlaybackPatch;"

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerTypeHookPatch,
        videoInformationPatch,
        settingsPatch,
        versionCheckPatch,
        fixRecycledBitmapPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_shorts_disable_background_playback")
        )

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_remove_background_playback_restrictions")
        )

        arrayOf(
            BackgroundPlaybackManagerFingerprint to "isBackgroundPlaybackAllowed",
            BackgroundPlaybackManagerShortsFingerprint to "isBackgroundShortsPlaybackAllowed",
        ).forEach { (fingerprint, integrationsMethod) ->
            fingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$integrationsMethod(Z)Z
                            move-result v$register 
                        """
                    )
                }
            }
        }

        fun MutableMethod.addBackgroundPlaybackIsPatchEnabledHook() {
            val returnInstruction = if (returnType == "Z") "return v0" else "return-void"

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isPatchEnabled()Z
                    move-result v0
                    if-eqz v0, :disabled
                    $returnInstruction
                    :disabled
                    nop
                """
            )
        }

        fun Fingerprint.addBackgroundPlaybackFeatureFlagHook(enable: Boolean) {
            val methodName = if (enable) "enableFeatureFlag" else "disableFeatureFlag"
            method.insertLiteralOverride(
                instructionMatches.first().index,
                "$EXTENSION_CLASS->$methodName(Z)Z"
            )
        }

        // Enable background playback option in YouTube settings
        BackgroundPlaybackSettingsFingerprint.originalMethod.apply {
            val booleanCalls = instructions.filter {
                it.getReference<MethodReference>()?.returnType == "Z"
            }

            booleanCalls[1].getReference<MethodReference>()!!
                .getMutableMethod().addBackgroundPlaybackIsPatchEnabledHook()
        }

        // Force allowing background play for videos labeled for kids.
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.addBackgroundPlaybackIsPatchEnabledHook()

        // Force allowing background play for Shorts.
        ShortsBackgroundPlaybackFeatureFlagFingerprint.addBackgroundPlaybackFeatureFlagHook(true)

        // Fix PiP buttons not working after locking/unlocking device screen.
        PipInputConsumerFeatureFlagFingerprint.addBackgroundPlaybackFeatureFlagHook(false)

        if (is_20_29_or_greater) {
            // Client flag that interferes with background playback of some video types.
            // Exact purpose is not clear and it's used in ~ 100 locations.
            NewPlayerTypeEnumFeatureFlag.addBackgroundPlaybackFeatureFlagHook(false)
        }
    }
}
