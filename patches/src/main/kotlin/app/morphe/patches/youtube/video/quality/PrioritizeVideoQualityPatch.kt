package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/playback/quality/PrioritizeVideoQualityPatch;"

internal val prioritizeVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        fixProtoLibraryPatch,
    )

    execute {
        settingsMenuVideoQualityGroup.add(
            SwitchPreference("morphe_prioritize_video_quality")
        )

        VideoStreamingDataConstructorFingerprint.match(
            VideoStreamingDataToStringFingerprint.originalClassDef
        ).let {
            // Clone method to preserve parameters.
            it.method.cloneMutableAndPreserveParameters().apply {
                // Must offset match indexes since cloning adds additional move instructions.
                val matchIndexOffset = numberOfParameterRegistersLogical
                val videoIdIndex = it.instructionMatches[1].index + matchIndexOffset
                val videoIdField = getInstruction<ReferenceInstruction>(videoIdIndex).reference

                val adaptiveFormatsIndex = it.instructionMatches.last().index + matchIndexOffset
                val adaptiveFormatsRegister = getInstruction<TwoRegisterInstruction>(adaptiveFormatsIndex).registerA

                val insertIndex = adaptiveFormatsIndex + 1
                val videoIdRegister = findFreeRegister(insertIndex, adaptiveFormatsRegister)

                addInstructions(
                    insertIndex,
                    """
                        # Get video id.
                        move-object/from16 v$videoIdRegister, p0
                        iget-object v$videoIdRegister, v$videoIdRegister, $videoIdField
                        
                        # Override adaptive formats.
                        invoke-static { v$videoIdRegister, v$adaptiveFormatsRegister }, $EXTENSION_CLASS_DESCRIPTOR->prioritizeVideoQuality(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
                        move-result-object v$adaptiveFormatsRegister
                    """
                )
            }
        }
    }
}
