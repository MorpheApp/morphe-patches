/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3847
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.shorts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.shortsautoplay.ReelPlaybackFingerprint
import app.morphe.patches.youtube.layout.shortsautoplay.ReelPlaybackLegacyFingerprint
import app.morphe.patches.youtube.layout.shortsautoplay.ReelPlaybackRepeatFingerprint
import app.morphe.patches.youtube.layout.shortsautoplay.getInstanceFieldGetFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_24_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
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
        versionCheckPatch,
        // Live streams are detected by the video information patch.
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_skip_live_shorts", summary = true)
        )

        val reelPlaybackFingerprint = if (is_21_42_or_greater) ReelPlaybackFingerprint
            else ReelPlaybackLegacyFingerprint

        // Skip to the next Short with the same user action the app uses for autoplay.
        val userActionMethodReference = reelPlaybackFingerprint.instructionMatches[1]
            .instruction.getReference<MethodReference>()!!
        val reelSequenceControllerMethodReference = reelPlaybackFingerprint.instructionMatches[2]
            .instruction.getReference<MethodReference>()!!
        // 21.42+ has an additional boolean parameter, which the app sets to false.
        val userActionParameterRegisters = if (is_21_42_or_greater) "v1, v2, v2, v2" else "v1, v2, v2"

        val getReelSequenceControllerInstruction = if (is_21_24_or_greater) {
            // The reel sequence controller is a field of the Shorts player.
            val reelSequenceControllerField = getInstanceFieldGetFingerprint(
                reelSequenceControllerMethodReference.definingClass
            ).match(reelPlaybackFingerprint.originalMethod)
                .instructionMatches.first().instruction.getReference<FieldReference>()!!

            "iget-object v1, p0, $reelSequenceControllerField"
        } else {
            // The Shorts player is the reel sequence controller.
            "move-object v1, p0"
        }

        // Add the interface and the helper method to the Shorts player.
        reelPlaybackFingerprint.classDef.apply {
            interfaces.add(EXTENSION_CONTROLLER_INTERFACE)

            methods.add(
                ImmutableMethod(
                    type,
                    "patch_skipToNextShort",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            new-instance v0, ${userActionMethodReference.definingClass}
                            const/4 v1, 0x3
                            const/4 v2, 0x0
                            invoke-direct { v0, $userActionParameterRegisters }, $userActionMethodReference
                            $getReelSequenceControllerInstruction
                            invoke-virtual { v1, v0 }, $reelSequenceControllerMethodReference
                            return-void
                        """
                    )
                }
            )
        }

        // Hand the Shorts player to extension code.
        val reelPlayerField = getInstanceFieldGetFingerprint(reelPlaybackFingerprint.classDef.type)
            .match(ReelPlaybackRepeatFingerprint.originalMethod)
            .instructionMatches.first().instruction.getReference<FieldReference>()!!

        ReelPlaybackRepeatFingerprint.method.apply {
            val register = findFreeRegister(0)

            addInstructions(
                0,
                """
                    iget-object v$register, p0, $reelPlayerField
                    invoke-static { v$register }, $EXTENSION_CLASS->setReelSequenceController($EXTENSION_CONTROLLER_INTERFACE)V
                """
            )
        }
    }
}
