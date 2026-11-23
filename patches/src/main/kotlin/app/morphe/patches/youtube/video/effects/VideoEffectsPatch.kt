/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.effects

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.videoeffects.EXTENSION_CLASS
import app.morphe.patches.shared.misc.videoeffects.videoEffectsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private val videoEffectsResourcePatch = patch {
    execute {
        copyResources(
            "videoeffects",
            ResourceGroup("drawable", "morphe_ic_video_effect.xml")
        )
    }
}

@Suppress("unused")
val videoEffectsPatch = videoEffectsPatch(
    description = "Adds an option to change the colors of videos or add visual effects, such as black and white. " +
            "This patch works with YouTube 20.51 and newer.",
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
            videoIdPatch,
            flyoutPatch,
            loadVideoHookPatch,
            playerFlyoutPreferences,
            videoEffectsResourcePatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    // 20.26 has an older video renderer without the passthrough shader.
    targetCompatible = { is_20_51_or_greater },
    preferenceScreen = PreferenceScreen.VIDEO,
    hookVideoId = ::hookVideoId,
    executeBlock = {
        addPlayerFlyoutMenuPreferences(
            SwitchPreference("morphe_video_effect_button_flyout")
        )

        MediaViewTypeFingerprint.let {
            // The server can change the surface view of videos without DRM to a secure surface view.
            // Videos with DRM get the secure surface view before these checks.
            listOf(it.instructionMatches[1], it.instructionMatches[4]).forEach { match ->
                match.getMethodCalled().apply {
                    findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->useSecureSurfaceView(Z)Z
                                move-result v$register
                            """
                        )
                    }
                }
            }

            // A/B tests can draw the videos of the hardware decoders on other surface views.
            // Software decoders pick their views elsewhere, and keep them.
            listOf(1, 3).forEach { matchIndex ->
                val viewType = MediaViewTypeEnumFingerprint.instructionMatches[matchIndex].fieldReference

                it.method.findInstructionIndicesReversedOrThrow(
                    fieldAccess(viewType, Opcode.SGET_OBJECT)
                ).forEach { index ->
                    val register = it.method.getInstruction<OneRegisterInstruction>(index).registerA

                    it.method.addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getMediaViewType(Ljava/lang/Enum;)Ljava/lang/Enum;
                            move-result-object v$register
                            check-cast v$register, ${viewType.type}
                        """
                    )
                }
            }
        }
    }
)

/**
 * Method that picks the view a video is drawn on. The renderer of the video effects draws only on
 * a surface view.
 */
internal object MediaViewTypeFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        returnType = "Z",
        filters = listOf(
            string(";vdgsv.")
        )
    ),
    returnType = "Z",
    parameters = listOf("L", "L", "Z", "Z"),
    filters = listOf(
        // The cold config and the player config change the surface view to a secure surface view.
        opcode(Opcode.IF_NE),
        methodCall(
            parameters = listOf(),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, MatchAfterImmediately()),
        methodCall(
            parameters = listOf(),
            returnType = "Z",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Types of the views a video can be drawn on.
 */
internal object MediaViewTypeEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("GL_SURFACE"),
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            location = MatchAfterWithin(10)
        ),
        string("DUAL_SURFACE"),
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            location = MatchAfterWithin(10)
        )
    )
)
