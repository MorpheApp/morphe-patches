package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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
            it.method.apply {
                val videoIdIndex = it.instructionMatches[1].index
                val videoIdField =
                    getInstruction<ReferenceInstruction>(videoIdIndex).reference
                val definingClassRegister =
                    getInstruction<TwoRegisterInstruction>(videoIdIndex).registerB

                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_setAdaptiveFormats",
                    listOf(
                        ImmutableMethodParameter(
                            "Ljava/util/List;",
                            null,
                            null
                        )
                    ),
                    "Ljava/util/List;",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    annotations,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            # Get video id.
                            iget-object v0, p0, $videoIdField
                            
                            # Override adaptive formats.
                            invoke-static { v0, p1 }, $EXTENSION_CLASS_DESCRIPTOR->prioritizeVideoQuality(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
                            move-result-object p1
                            
                            return-object p1
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)

                val adaptiveFormatsIndex = it.instructionMatches.last().index
                val adaptiveFormatsRegister =
                    getInstruction<TwoRegisterInstruction>(adaptiveFormatsIndex).registerA

                addInstructions(
                    adaptiveFormatsIndex + 1,
                    """
                        invoke-direct { v$definingClassRegister,  v$adaptiveFormatsRegister }, $helperMethod
                        move-result-object v$adaptiveFormatsRegister
                    """
                )
            }
        }
    }
}
