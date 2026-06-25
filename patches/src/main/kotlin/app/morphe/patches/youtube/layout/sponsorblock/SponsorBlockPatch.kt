package app.morphe.patches.youtube.layout.sponsorblock

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.injectVisibilityCheckCall
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
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

private const val SB_PREFERENCES_PACKAGE = "app.morphe.extension.youtube.sponsorblock.preferences"
private const val SEGMENT_CATEGORY_PREFERENCE_TAG =
    "app.morphe.extension.shared.sponsorblock.objects.SegmentCategoryPreference"

private class SbPreference(
    settingKey: String,
    titleResourceKey: String,
    summaryResourceKey: String?,
    tag: String
) : BasePreference(settingKey, titleResourceKey, summaryResourceKey, null, null, null, tag)

private fun sbSwitch(
    settingKey: String,
    resourceKey: String,
    tag: String = "SwitchPreference"
): BasePreference = SbPreference(settingKey, resourceKey, "${resourceKey}_summary", tag)

private fun sbSwitch(settingKey: String): BasePreference = SbPreference(
    settingKey = settingKey,
    titleResourceKey = "morphe_${settingKey}_title",
    summaryResourceKey = "morphe_${settingKey}_summary",
    tag = "SwitchPreference"
)

private fun sbDurationList(settingKey: String, resourceKey: String) = ListPreference(
    key = settingKey,
    titleKey = resourceKey,
    entriesKey = "${resourceKey}_entries",
    entryValuesKey = "${resourceKey}_entry_values"
)

private fun sbClickPreference(settingKey: String, resourceKey: String, className: String) =
    NonInteractivePreference(
        key = settingKey,
        titleKey = resourceKey,
        summaryKey = "${resourceKey}_summary",
        tag = "$SB_PREFERENCES_PACKAGE.$className",
        selectable = true
    )

private fun sbCategoryColorPreference(settingKey: String): BasePreference =
    object : BasePreference(settingKey, null, null, null, null, null, SEGMENT_CATEGORY_PREFERENCE_TAG) {}

private fun sbMasterToggle() = sbSwitch("sb_enabled")

private fun sbAppearanceCategory() = PreferenceCategory(
    key = null,
    titleKey = "morphe_sb_appearance_category",
    sorting = PreferenceScreenPreference.Sorting.UNSORTED,
    preferences = setOf(
        sbSwitch("sb_voting_button", "morphe_sb_enable_voting"),
        sbSwitch("sb_compact_skip_button", "morphe_sb_enable_compact_skip_button"),
        sbSwitch("sb_auto_hide_skip_button", "morphe_sb_enable_auto_hide_skip_segment_button"),
        sbDurationList("sb_auto_hide_skip_button_duration", "morphe_sb_auto_hide_skip_button_duration"),
        sbSwitch("sb_toast_on_skip"),
        sbDurationList("sb_toast_on_skip_duration", "morphe_sb_toast_on_skip_duration"),
        sbSwitch("sb_video_length_without_segments", "morphe_sb_general_time_without"),
        sbSwitch("sb_square_layout", "morphe_sb_square_layout")
    )
)

private fun sbSegmentsCategory() = PreferenceCategory(
    key = null,
    titleKey = "morphe_sb_diff_segments",
    sorting = PreferenceScreenPreference.Sorting.UNSORTED,
    preferences = setOf(
        sbCategoryColorPreference("sb_sponsor_color"),
        sbCategoryColorPreference("sb_selfpromo_color"),
        sbCategoryColorPreference("sb_interaction_color"),
        sbCategoryColorPreference("sb_highlight_color"),
        sbCategoryColorPreference("sb_intro_color"),
        sbCategoryColorPreference("sb_outro_color"),
        sbCategoryColorPreference("sb_preview_color"),
        sbCategoryColorPreference("sb_hook_color"),
        sbCategoryColorPreference("sb_filler_color"),
        sbCategoryColorPreference("sb_music_offtopic_color")
    )
)

private fun sbCreateSegmentCategory() = PreferenceCategory(
    key = null,
    titleKey = "morphe_sb_create_segment_category",
    sorting = PreferenceScreenPreference.Sorting.UNSORTED,
    preferences = setOf(
        sbSwitch(
            settingKey = "sb_create_new_segment",
            resourceKey = "morphe_sb_enable_create_segment",
            tag = "$SB_PREFERENCES_PACKAGE.SponsorBlockCreateSegmentSwitchPreference"
        ),
        TextPreference(
            key = "sb_create_new_segment_step",
            titleKey = "morphe_sb_general_adjusting",
            summaryKey = "morphe_sb_general_adjusting_summary",
            tag = "$SB_PREFERENCES_PACKAGE.SponsorBlockSegmentStepPreference",
            inputType = InputType.NUMBER
        ),
        NonInteractivePreference(
            key = "morphe_sb_guidelines",
            titleKey = "morphe_sb_guidelines_preference_title",
            summaryKey = "morphe_sb_guidelines_preference_summary",
            tag = "$SB_PREFERENCES_PACKAGE.SponsorBlockGuidelinesPreference",
            selectable = true
        )
    )
)

private fun sbGeneralCategory() = PreferenceCategory(
    key = null,
    titleKey = "morphe_sb_general",
    sorting = PreferenceScreenPreference.Sorting.UNSORTED,
    preferences = setOf(
        sbSwitch("sb_toast_on_connection_error", "morphe_sb_toast_on_connection_error"),
        sbSwitch("sb_track_skip_count", "morphe_sb_general_skipcount"),
        TextPreference(
            key = "sb_min_segment_duration",
            titleKey = "morphe_sb_general_min_duration",
            summaryKey = "morphe_sb_general_min_duration_summary",
            inputType = InputType.NUMBER_DECIMAL
        ),
        TextPreference(
            key = "sb_private_user_id_Do_Not_Share",
            titleKey = "morphe_sb_general_uuid",
            summaryKey = "morphe_sb_general_uuid_summary",
            tag = "$SB_PREFERENCES_PACKAGE.SponsorBlockPrivateUserIdPreference",
        ),
        sbClickPreference(
            settingKey = "morphe_sb_change_api_url",
            resourceKey = "morphe_sb_general_api_url",
            className = "SponsorBlockApiUrlPreference",
        ),
        sbClickPreference(
            settingKey = "morphe_sb_channel_whitelist",
            resourceKey = "morphe_sb_channel_whitelist",
            className = "SponsorBlockChannelWhitelistPreference"
        ),
        sbSwitch("sb_toast_on_whitelisted_channel", "morphe_sb_toast_on_whitelisted_channel"),
        TextPreference(
            key = null,
            titleKey = "morphe_sb_settings_ie",
            summaryKey = "morphe_sb_settings_ie_summary",
            tag = "$SB_PREFERENCES_PACKAGE.SponsorBlockImportExportPreference"
        )
    )
)

private val sponsorBlockResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        resourceMappingPatch,
        legacyPlayerControlsPatch
    )

    execute {
        PreferenceScreen.SPONSORBLOCK.addPreferences(
            sbMasterToggle(),
            sbAppearanceCategory(),
            sbSegmentsCategory(),
            sbCreateSegmentCategory(),
            sbGeneralCategory(),
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
                        tag = "app.morphe.extension.shared.sponsorblock.ui.SponsorBlockAboutPreference",
                        selectable = true
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
                "morphe_sb_adjust_bold.xml",
                "morphe_sb_backward.xml",
                "morphe_sb_backward_bold.xml",
                "morphe_sb_compare.xml",
                "morphe_sb_compare_bold.xml",
                "morphe_sb_edit.xml",
                "morphe_sb_edit_bold.xml",
                "morphe_sb_forward.xml",
                "morphe_sb_forward_bold.xml",
                "morphe_sb_logo.xml",
                "morphe_sb_logo_bold.xml",
                "morphe_sb_publish.xml",
                "morphe_sb_publish_bold.xml",
                "morphe_sb_voting.xml",
                "morphe_sb_voting_bold.xml"
            )
        ).forEach { resourceGroup ->
            copyResources("sponsorblock", resourceGroup)
        }

        addTopControl(
            "sponsorblock",
            "@+id/morphe_sb_voting_button",
            "@+id/morphe_sb_create_segment_button"
        )
    }
}

internal const val EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS =
    "Lapp/morphe/extension/youtube/sponsorblock/YouTubeSponsorBlockConfig;"
private const val EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/CreateSegmentButton;"
private const val EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/VotingButton;"
private const val EXTENSION_SPONSORBLOCK_VIEW_CONTROLLER_CLASS =
    "Lapp/morphe/extension/youtube/sponsorblock/ui/SponsorBlockViewController;"

@Suppress("unused")
val sponsorBlockPatch = bytecodePatch(
    name = "SponsorBlock",
    description = "Adds options to enable and configure SponsorBlock, which can skip undesired video segments such as sponsored content."
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch,
        videoIdPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        legacyPlayerControlsPatch,
        sponsorBlockResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Hook the video time methods.
        videoTimeHook(
            EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS,
            "setVideoTime",
        )

        hookBackgroundPlayVideoId(
            EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS +
                "->setCurrentVideoId(Ljava/lang/String;)V",
        )

        // Set seekbar draw rectangle.
        val rectangleFieldName: FieldReference
        RectangleFieldInvalidatorFingerprint.let {
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
        SeekbarOnDrawFingerprint.let {
            it.method.apply {
                // Set seekbar thickness.
                val thicknessIndex = it.instructionMatches.last().index
                val thicknessRegister = getInstruction<OneRegisterInstruction>(thicknessIndex).registerA
                addInstruction(
                    thicknessIndex + 1,
                    "invoke-static { v$thicknessRegister }, " +
                            "$EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS->setSeekbarThickness(I)V",
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
                            "$EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS->" +
                            "drawSegmentTimeBars(Landroid/graphics/Canvas;F)V",
                )

                // Set seekbar bounds.
                addInstructions(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v0, v0, $rectangleFieldName
                        invoke-static { v0 }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS->setSeekbarRectangle(Landroid/graphics/Rect;)V
                    """
                )
            }
        }

        // Change visibility of the buttons.
        initializeTopControl(EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS)
        injectVisibilityCheckCall(EXTENSION_CREATE_SEGMENT_BUTTON_CONTROLLER_CLASS)

        initializeTopControl(EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS)
        injectVisibilityCheckCall(EXTENSION_VOTING_BUTTON_CONTROLLER_CLASS)

        // Show skip button when player overlay is active.
        injectVisibilityCheckCall(EXTENSION_SPONSORBLOCK_VIEW_CONTROLLER_CLASS)

        // Append the new time to the player layout.
        AppendTimeFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS->appendTimeWithoutSegments(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """
                )
            }
        }

        // Initialize the player controller.
        onCreateHook(EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS, "initialize")

        // Initialize the SponsorBlock view.
        ControlsOverlayFingerprint.let {
            it.method.apply {
                val checkCastIndex = it.instructionMatches.last().index
                val frameLayoutRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static {v$frameLayoutRegister}, $EXTENSION_SPONSORBLOCK_VIEW_CONTROLLER_CLASS->initialize(Landroid/view/ViewGroup;)V",
                )
            }
        }

        AdProgressTextViewVisibilityFingerprint.method.apply {
            val index = indexOfAdProgressTextViewVisibilityInstruction(this)
            val register = getInstruction<FiveRegisterInstruction>(index).registerD

            addInstructionsAtControlFlowLabel(
                index,
                "invoke-static { v$register }, $EXTENSION_SEGMENT_PLAYBACK_CONTROLLER_CLASS->setAdProgressTextVisibility(I)V"
            )
        }
    }
}
