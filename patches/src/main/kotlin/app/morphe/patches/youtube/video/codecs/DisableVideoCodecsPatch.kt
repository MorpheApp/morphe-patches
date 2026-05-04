package app.morphe.patches.youtube.video.codecs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableVideoCodecsPatch;"

@Suppress("unused")
val disableVideoCodecsPatch = bytecodePatch(
    name = "Disable video codecs",
    description = "Adds options to disable HDR and VP9 codecs.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // TODO: Replace this with Fingerprint.matchAll()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach

            classDef.methods.forEach { method ->
                val instructionsIterable = method.implementation?.instructions ?: return@forEach
                val targetIndices = instructionsIterable.mapIndexedNotNull { index, instruction ->
                    val reference = instruction.getReference<MethodReference>()
                    if (reference?.definingClass == $$"Landroid/view/Display$HdrCapabilities;" &&
                        reference.name == "getSupportedHdrTypes"
                    ) {
                        return@mapIndexedNotNull index
                    }
                    null
                }

                if (targetIndices.isNotEmpty()) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)

                    targetIndices.reversed().forEach { index ->
                        val instruction = mutableMethod.getInstruction<FiveRegisterInstruction>(index)
                        val register = instruction.registerC

                        mutableMethod.replaceInstruction(
                            index,
                            $$"invoke-static/range { v$$register .. v$$register }, $$EXTENSION_CLASS->disableHdrVideo(Landroid/view/Display$HdrCapabilities;)[I"
                        )
                    }
                }
            }
        }

        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_disable_hdr_video"),
            SwitchPreference(
                key = "morphe_force_avc_codec",
                tag = "app.morphe.extension.youtube.settings.preference.ForceAVCSwitchPreference"
            )
        )

        Vp9CapabilityFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS->allowVP9()Z
                move-result v0
                if-nez v0, :default
                return v0
                :default
                nop
            """
        )
    }
}