/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch;"

internal val preferredCaptionLanguagePatch = bytecodePatch(
    description = "Adds an option to automatically select captions in your preferred language (provider subtitles first, then auto-translated).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            ListPreference("morphe_preferred_caption_language")
        )

        DefaultCaptionTrackFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructions(
                    index,
                    """
                        invoke-static { p0, v$register }, $EXTENSION_CLASS->getPreferredCaptionTrack(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$register
                        check-cast v$register, $returnType
                    """
                )
            }
        }

        SetSubtitleTrackFingerprint.method.apply {
            val trackType = parameterTypes[0]
            addInstructions(
                0,
                """
                    invoke-static { p0, p1, p2 }, $EXTENSION_CLASS->onSetSubtitleTrack(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $trackType
                """
            )
        }

        onCreateHook(EXTENSION_CLASS, "newVideoStarted")
    }
}
