package app.morphe.patches.youtube.video.codecs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

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
        PreferenceScreen.VIDEO.addPreferences(
            SwitchPreference("morphe_disable_hdr_video", summaryKey = null),
            SwitchPreference(
                key = "morphe_force_avc_codec",
                tag = "app.morphe.extension.youtube.settings.preference.ForceAVCSwitchPreference",
                summaryKey = null
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

        val methodCall = methodCall(
            definingClass = $$"Landroid/view/Display$HdrCapabilities;",
            name = "getSupportedHdrTypes",
        )

        Fingerprint(
            filters = listOf(methodCall),
            custom = { _, classDef ->
                !classDef.type.startsWith("Lapp/morphe/")
            }
        ).matchAll().forEach { match ->
            match.method.apply {
                findInstructionIndicesReversedOrThrow(methodCall).forEach { index ->
                    val instruction = getInstruction<FiveRegisterInstruction>(index)
                    val register = instruction.registerC

                    replaceInstruction(
                        index,
                        "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->disableHdrVideo(Landroid/view/Display\$HdrCapabilities;)[I"
                    )
                }
            }
        }
    }
}