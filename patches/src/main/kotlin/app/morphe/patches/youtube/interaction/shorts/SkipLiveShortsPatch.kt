/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3847
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.shorts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.shortsautoplay.ReelPlaybackFingerprint
import app.morphe.patches.youtube.layout.shortsautoplay.ReelPlaybackRepeatFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/SkipLiveShortsPatch;"
private const val EXTENSION_CONTROLLER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/SkipLiveShortsPatch$ReelSequenceController;"

@Suppress("unused")
val skipLiveShortsPatch = bytecodePatch(
    name = "Skip live Shorts",
    description = "Automatically skips live streams that appear in the Shorts feed."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_skip_live_shorts", summary = true)
        )

        // The Shorts player is advanced with the same user action the app uses for autoplay.
        val userActionMethodReference = ReelPlaybackFingerprint.instructionMatches[1]
            .getInstruction<ReferenceInstruction>().reference as MethodReference
        val reelSequenceControllerMethodReference = ReelPlaybackFingerprint.instructionMatches[2]
            .getInstruction<ReferenceInstruction>().reference as MethodReference

        ReelPlaybackRepeatFingerprint.method.apply {
            val getReelSequenceControllerIndex = indexOfFirstInstructionOrThrow {
                val reference = getReference<FieldReference>()
                opcode == Opcode.IGET_OBJECT &&
                        reference?.definingClass == definingClass &&
                        reference.type == reelSequenceControllerMethodReference.definingClass
            }
            val getReelSequenceControllerReference =
                getInstruction<ReferenceInstruction>(getReelSequenceControllerIndex).reference

            // Add the interface and the helper method that extension code calls to skip a Short.
            val helperMethod = ImmutableMethod(
                definingClass,
                "patch_skipToNextShort",
                listOf(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        new-instance v0, ${userActionMethodReference.definingClass}
                        const/4 v1, 0x3
                        const/4 v2, 0x0
                        invoke-direct { v0, v1, v2, v2 }, $userActionMethodReference
                        iget-object v3, p0, $getReelSequenceControllerReference
                        invoke-virtual { v3, v0 }, $reelSequenceControllerMethodReference
                        return-void
                    """
                )
            }

            ReelPlaybackRepeatFingerprint.classDef.interfaces.add(EXTENSION_CONTROLLER_INTERFACE)
            ReelPlaybackRepeatFingerprint.classDef.methods.add(helperMethod)

            // Hand the Shorts player instance to extension code.
            addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->setReelSequenceController($EXTENSION_CONTROLLER_INTERFACE)V"
            )
        }
    }
}
