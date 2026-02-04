package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.EXTENSION_VIDEO_QUALITY_INTERFACE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/playback/quality/HidePremiumVideoQualityPatch;"

internal val hidePremiumVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    execute {
        settingsMenuVideoQualityGroup.add(
            SwitchPreference("morphe_hide_premium_video_quality")
        )

        // Class name is obfuscated in 21.02+
        val videoQualityArray = DefaultOverflowOverlayOnClickFingerprint.match()
            .instructionMatches.last().instruction.getReference<FieldReference>()!!.type

        // To avoid ClassCastException, declare the new array as original video quality class instead of [EXTENSION_VIDEO_QUALITY_INTERFACE]
        HidePremiumVideoQualityGetArrayFingerprint.method.addInstructions(
            0,
            """
                new-array p1, p1, $videoQualityArray
                return-object p1
            """
        )

        val currentVideoFormatConstructorFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
            returnType = "V",
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IPUT_OBJECT,
                    definingClass = "this",
                    type = videoQualityArray
                )
            )
        )

        currentVideoFormatConstructorFingerprint.match(
            CurrentVideoFormatToStringFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->hidePremiumVideoQuality([$EXTENSION_VIDEO_QUALITY_INTERFACE)[Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $videoQualityArray
                    """
                )
            }
        }
    }
}
