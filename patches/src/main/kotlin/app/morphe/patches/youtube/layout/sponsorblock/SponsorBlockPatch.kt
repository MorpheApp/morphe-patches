package app.morphe.patches.youtube.layout.sponsorblock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.playerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.patches.youtube.shared.SeekbarFingerprint
import app.morphe.patches.youtube.shared.SeekbarOnDrawFingerprint
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val sponsorBlockResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        resourceMappingPatch,
        playerControlsPatch,
    )

    execute {
        PreferenceScreen.SPONSORBLOCK.addPreferences(
            // SB setting is old code with lots of custom preferences and updating behavior.
            // Added as a preference group and not a fragment so the preferences are searchable.
            PreferenceCategory(
                key = "morphe_settings_screen_10_sponsorblock",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = emptySet(), // Preferences are added by custom class at runtime.
                tag = "app.morphe.extension.youtube.sponsorblock.ui.SponsorBlockPreferenceGroup"
            ),
            PreferenceCategory(
                key = "morphe_sb_stats",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = emptySet(), // Preferences are added by custom class at runtime.
                tag = "app.morphe.extension.youtube.sponsorblock.ui.SponsorBlockStatsPreferenceCategory"
            ),
            PreferenceCategory(
                key = "morphe_sb_about",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_sb_about_api",
                        tag = "app.morphe.extension.youtube.sponsorblock.ui.SponsorBlockAboutPreference",
                        selectable = true,
                    )
                )
            )
        )

        arrayOf(
            ResourceGroup(
                "layout",
                "morphe_sb_inline_sponsor_overlay.xml",
                "morphe_sb_new_segment.xml",
                "morphe_sb_skip_sponsor_button.xml",
            ),
            ResourceGroup(
                "drawable",
                "morphe_sb_adjust.xml",
                "morphe_sb_backward.xml",
                "morphe_sb_compare.xml",
                "morphe_sb_edit.xml",
                "morphe_sb_forward.xml",
                "morphe_sb_logo.xml",
                "morphe_sb_logo_bold.xml",
                "morphe_sb_publish.xml",
                "morphe_sb_voting.xml",
            )
        ).forEach { resourceGroup ->
            copyResources("sponsorblock", resourceGroup)
        }

        addTopControl("sponsorblock")
    }
}

internal const val EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/sponsorblock/SegmentPlaybackController;"
private const val EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/CreateSegmentButton;"
private const val EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/VotingButton;"
private const val EXTENSION_SPONSORBLOCK_VIEW_CONTROLLER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/SponsorBlockViewController;"

@Suppress("unused")
val sponsorBlockPatch = bytecodePatch(
    name = "SponsorBlock",
    description = "Adds options to enable and configure SponsorBlock, which can skip undesired video segments such as sponsored content.",
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch,
        videoIdPatch,
        // Required to skip segments on time.
        videoInformationPatch,
        // Used to prevent SponsorBlock from running on Shorts because SponsorBlock does not yet support Shorts.
        playerTypeHookPatch,
        playerControlsPatch,
        sponsorBlockResourcePatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        // Hook the video time methods.
        videoTimeHook(
            EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR,
            "setVideoTime",
        )

        hookBackgroundPlayVideoId(
            EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR +
                "->setCurrentVideoId(Ljava/lang/String;)V",
        )

        // Set seekbar draw rectangle.
        val rectangleFieldName: FieldReference
        RectangleFieldInvalidatorFingerprint.match(
            SeekbarFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val rectangleIndex = indexOfFirstInstructionReversedOrThrow(
                    it.instructionMatches.first().index
                ) {
                    getReference<FieldReference>()?.type == "Landroid/graphics/Rect;"
                }
                rectangleFieldName = getInstruction<ReferenceInstruction>(rectangleIndex).reference as FieldReference
            }
        }

        // Seekbar drawing.

        // Shared fingerprint and indexes may have changed.
        SeekbarOnDrawFingerprint.clearMatch()
        // Cannot match using original immutable class because
        // class may have been modified by other patches
        SeekbarOnDrawFingerprint.match(SeekbarFingerprint.classDef).let {
            it.method.apply {
                // Set seekbar thickness.
                val thicknessIndex = it.instructionMatches.last().index
                val thicknessRegister = getInstruction<OneRegisterInstruction>(thicknessIndex).registerA
                addInstruction(
                    thicknessIndex + 1,
                    "invoke-static { v$thicknessRegister }, " +
                            "$EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->setSeekbarThickness(I)V",
                )

                // Find the drawCircle call and draw the segment before it.
                val drawCircleIndex = indexOfFirstInstructionReversedOrThrow {
                    getReference<MethodReference>()?.name == "drawCircle"
                }
                val drawCircleInstruction = getInstruction<FiveRegisterInstruction>(drawCircleIndex)
                val canvasInstanceRegister = drawCircleInstruction.registerC
                val centerYRegister = drawCircleInstruction.registerE

                addInstruction(
                    drawCircleIndex,
                    "invoke-static { v$canvasInstanceRegister, v$centerYRegister }, " +
                            "$EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->" +
                            "drawSegmentTimeBars(Landroid/graphics/Canvas;F)V",
                )

                // Set seekbar bounds.
                addInstructions(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v0, v0, $rectangleFieldName
                        invoke-static { v0 }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->setSeekbarRectangle(Landroid/graphics/Rect;)V
                    """
                )
            }
        }

        // Change visibility of the buttons.
        initializeTopControl(EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS_DESCRIPTOR)

        initializeTopControl(EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR)
        injectVisibilityCheckCall(EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS_DESCRIPTOR)

        // Append the new time to the player layout.
        AppendTimeFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->appendTimeWithoutSegments(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // Initialize the player controller.
        onCreateHook(EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR, "initialize")

        // Initialize the SponsorBlock view.
        ControlsOverlayFingerprint.match(LayoutConstructorFingerprint.originalClassDef).let {
            val checkCastIndex = it.instructionMatches.last().index
            it.method.apply {
                val frameLayoutRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static {v$frameLayoutRegister}, $EXTENSION_SPONSORBLOCK_VIEW_CONTROLLER_CLASS_DESCRIPTOR->initialize(Landroid/view/ViewGroup;)V",
                )
            }
        }

        AdProgressTextViewVisibilityFingerprint.method.apply {
            val index = indexOfAdProgressTextViewVisibilityInstruction(this)
            val register = getInstruction<FiveRegisterInstruction>(index).registerD

            addInstructionsAtControlFlowLabel(
                index,
                "invoke-static { v$register }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS_DESCRIPTOR->setAdProgressTextVisibility(I)V"
            )
        }
    }
}
