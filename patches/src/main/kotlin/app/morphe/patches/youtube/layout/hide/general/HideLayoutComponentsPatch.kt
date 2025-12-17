package app.morphe.patches.youtube.layout.hide.general

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal var albumCardId = -1L
    private set
internal var crowdfundingBoxId = -1L
    private set
internal var filterBarHeightId = -1L
    private set
internal var relatedChipCloudMarginId = -1L
    private set
internal var barContainerHeightId = -1L
    private set

private val hideLayoutComponentsResourcePatch = resourcePatch {
    dependsOn(resourceMappingPatch)

    execute {
        albumCardId = getResourceId(
            ResourceType.LAYOUT,
            "album_card",
        )

        crowdfundingBoxId = getResourceId(
            ResourceType.LAYOUT,
            "donation_companion",
        )

        relatedChipCloudMarginId = getResourceId(
            ResourceType.LAYOUT,
            "related_chip_cloud_reduced_margins",
        )

        filterBarHeightId = getResourceId(
            ResourceType.DIMEN,
            "filter_bar_height",
        )

        barContainerHeightId = getResourceId(
            ResourceType.DIMEN,
            "bar_container_height",
        )
    }
}

private const val LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/components/LayoutComponentsFilter;"
private const val DESCRIPTION_COMPONENTS_FILTER_CLASS_NAME =
    "Lapp/morphe/extension/youtube/patches/components/DescriptionComponentsFilter;"
private const val COMMENTS_FILTER_CLASS_NAME =
    "Lapp/morphe/extension/youtube/patches/components/CommentsFilter;"
private const val CUSTOM_FILTER_CLASS_NAME =
    "Lapp/morphe/extension/youtube/patches/components/CustomFilter;"
private const val KEYWORD_FILTER_CLASS_NAME =
    "Lapp/morphe/extension/youtube/patches/components/KeywordContentFilter;"

val hideLayoutComponentsPatch = bytecodePatch(
    name = "Hide layout components",
    description = "Adds options to hide general layout components.",

) {
    dependsOn(
        lithoFilterPatch,
        settingsPatch,
        hideLayoutComponentsResourcePatch,
        navigationBarHookPatch,
        versionCheckPatch,
        resourceMappingPatch
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
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_description_components_screen",
                preferences = setOf(
                    SwitchPreference("morphe_hide_ai_generated_video_summary_section"),
                    SwitchPreference("morphe_hide_ask_section"),
                    SwitchPreference("morphe_hide_attributes_section"),
                    SwitchPreference("morphe_hide_chapters_section"),
                    SwitchPreference("morphe_hide_featured_section"),
                    SwitchPreference("morphe_hide_info_cards_section"),
                    SwitchPreference("morphe_hide_how_this_was_made_section"),
                    SwitchPreference("morphe_hide_hype_points"),
                    SwitchPreference("morphe_hide_key_concepts_section"),
                    SwitchPreference("morphe_hide_podcast_section"),
                    SwitchPreference("morphe_hide_description_subscribe_button"),
                    SwitchPreference("morphe_hide_transcript_section"),
                ),
            ),
            PreferenceScreenPreference(
                "morphe_comments_screen",
                preferences = setOf(
                    SwitchPreference("morphe_hide_comments_ai_chat_summary"),
                    SwitchPreference("morphe_hide_comments_ai_summary"),
                    SwitchPreference("morphe_hide_comments_channel_guidelines"),
                    SwitchPreference("morphe_hide_comments_by_members_header"),
                    SwitchPreference("morphe_hide_comments_section"),
                    SwitchPreference("morphe_hide_comments_community_guidelines"),
                    SwitchPreference("morphe_hide_comments_create_a_short_button"),
                    SwitchPreference("morphe_hide_comments_emoji_and_timestamp_buttons"),
                    SwitchPreference("morphe_hide_comments_preview_comment"),
                    SwitchPreference("morphe_hide_comments_thanks_button"),
                ),
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
            ),
            SwitchPreference("morphe_hide_channel_bar"),
            SwitchPreference("morphe_hide_channel_watermark"),
            SwitchPreference("morphe_hide_emergency_box"),
            SwitchPreference("morphe_hide_info_panels"),
            SwitchPreference("morphe_hide_join_membership_button"),
            SwitchPreference("morphe_hide_medical_panels"),
            SwitchPreference("morphe_hide_quick_actions"),
            SwitchPreference("morphe_hide_related_videos"),
            SwitchPreference("morphe_hide_subscribers_community_guidelines"),
            SwitchPreference("morphe_hide_timed_reactions"),
        )

        PreferenceScreen.FEED.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_keyword_content_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_hide_keyword_content_home"),
                    SwitchPreference("morphe_hide_keyword_content_subscriptions"),
                    SwitchPreference("morphe_hide_keyword_content_search"),
                    TextPreference("morphe_hide_keyword_content_phrases", inputType = InputType.TEXT_MULTI_LINE),
                    NonInteractivePreference(
                        key = "morphe_hide_keyword_content_about",
                        tag = "app.morphe.extension.shared.settings.preference.BulletPointPreference"
                    ),
                    NonInteractivePreference(
                        key = "morphe_hide_keyword_content_about_whole_words",
                        tag = "app.morphe.extension.youtube.settings.preference.HtmlPreference",
                    ),
                ),
            ),
            PreferenceScreenPreference(
                key = "morphe_hide_filter_bar_screen",
                preferences = setOf(
                    SwitchPreference("morphe_hide_filter_bar_feed_in_feed"),
                    SwitchPreference("morphe_hide_filter_bar_feed_in_related_videos"),
                    SwitchPreference("morphe_hide_filter_bar_feed_in_search"),
                    SwitchPreference("morphe_hide_filter_bar_feed_in_history"),
                ),
            ),
            PreferenceScreenPreference(
                key = "morphe_channel_screen",
                preferences = setOf(
                    SwitchPreference("morphe_hide_for_you_shelf"),
                    SwitchPreference("morphe_hide_links_preview"),
                    SwitchPreference("morphe_hide_members_shelf"),
                    SwitchPreference("morphe_hide_visit_community_button"),
                    SwitchPreference("morphe_hide_visit_store_button"),
                ),
            ),
            SwitchPreference("morphe_hide_album_cards"),
            SwitchPreference("morphe_hide_artist_cards"),
            SwitchPreference("morphe_hide_community_posts"),
            SwitchPreference("morphe_hide_compact_banner"),
            SwitchPreference("morphe_hide_crowdfunding_box"),
            SwitchPreference("morphe_hide_chips_shelf"),
            SwitchPreference("morphe_hide_expandable_card"),
            SwitchPreference("morphe_hide_floating_microphone_button"),
            SwitchPreference(
                key = "morphe_hide_horizontal_shelves",
                tag = "app.morphe.extension.shared.settings.preference.BulletPointSwitchPreference"
            ),
            SwitchPreference("morphe_hide_image_shelf"),
            SwitchPreference("morphe_hide_latest_posts"),
            SwitchPreference("morphe_hide_mix_playlists"),
            SwitchPreference("morphe_hide_movies_section"),
            SwitchPreference("morphe_hide_notify_me_button"),
            SwitchPreference("morphe_hide_playables"),
            SwitchPreference("morphe_hide_show_more_button"),
            SwitchPreference("morphe_hide_surveys"),
            SwitchPreference("morphe_hide_ticket_shelf"),
            SwitchPreference("morphe_hide_video_recommendation_labels"),
            SwitchPreference("morphe_hide_view_count"),
            SwitchPreference("morphe_hide_upload_time"),
            SwitchPreference("morphe_hide_doodles"),
        )

        PreferenceScreen.GENERAL_LAYOUT.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_custom_filter_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_custom_filter"),
                    TextPreference("morphe_custom_filter_strings", inputType = InputType.TEXT_MULTI_LINE),
                ),
            ),
        )

        addLithoFilter(LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR)
        addLithoFilter(DESCRIPTION_COMPONENTS_FILTER_CLASS_NAME)
        addLithoFilter(COMMENTS_FILTER_CLASS_NAME)
        addLithoFilter(KEYWORD_FILTER_CLASS_NAME)
        addLithoFilter(CUSTOM_FILTER_CLASS_NAME)

        // region Mix playlists

        ParseElementFromBufferFingerprint.method.apply {
            val startIndex = ParseElementFromBufferFingerprint.instructionMatches.first().index
            val insertIndex = startIndex + 1

            val byteArrayParameter = "p3"
            val conversionContextRegister = getInstruction<TwoRegisterInstruction>(startIndex).registerA
            val returnEmptyComponentInstruction = instructions.last { it.opcode == Opcode.INVOKE_STATIC }
            val returnEmptyComponentRegister = (returnEmptyComponentInstruction as FiveRegisterInstruction).registerC
            val freeRegister = findFreeRegister(insertIndex, conversionContextRegister, returnEmptyComponentRegister)

            addInstructionsWithLabels(
                insertIndex,
                """
                    invoke-static { v$conversionContextRegister, $byteArrayParameter }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->filterMixPlaylists(Ljava/lang/Object;[B)Z
                    move-result v$freeRegister 
                    if-eqz v$freeRegister, :show
                    move-object v$returnEmptyComponentRegister, p1   # Required for 19.47
                    goto :return_empty_component
                    :show
                    nop
                """,
                ExternalLabel("return_empty_component", returnEmptyComponentInstruction),
            )
        }

        // endregion

        // region Watermark (legacy code for old versions of YouTube)

        ShowWatermarkFingerprint.match(
            PlayerOverlayFingerprint.originalClassDef,
        ).method.apply {
            val index = implementation!!.instructions.size - 5

            removeInstruction(index)
            addInstructions(
                index,
                """
                    invoke-static {}, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->showWatermark()Z
                    move-result p2
                """,
            )
        }

        // endregion

        // region Show more button

        (if (is_20_26_or_greater) HideShowMoreButtonFingerprint else HideShowMoreLegacyButtonFingerprint).let {
            it.method.apply {
                val moveRegisterIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(moveRegisterIndex).registerA

                val insertIndex = moveRegisterIndex + 1
                addInstruction(
                    insertIndex,
                    "invoke-static { v$viewRegister }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR" +
                            "->hideShowMoreButton(Landroid/view/View;)V",
                )
            }
        }

        // endregion

        // region crowdfunding box
        CrowdfundingBoxFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val objectRegister = getInstruction<TwoRegisterInstruction>(insertIndex).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static {v$objectRegister}, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR" +
                        "->hideCrowdfundingBox(Landroid/view/View;)V",
                )
            }
        }

        // endregion

        // region hide album cards

        AlbumCardsFingerprint.let {
            it.method.apply {
                val checkCastAnchorIndex = it.instructionMatches.last().index
                val insertIndex = checkCastAnchorIndex + 1
                val register = getInstruction<OneRegisterInstruction>(checkCastAnchorIndex).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR" +
                        "->hideAlbumCard(Landroid/view/View;)V",
                )
            }
        }

        // endregion

        // region hide floating microphone

        ShowFloatingMicrophoneButtonFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->hideFloatingMicrophoneButton(Z)Z
                        move-result v$register
                    """,
                )
            }
        }

        // endregion

        // region 'Yoodles'

        YouTubeDoodlesImageViewFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.name == "setImageDrawable"
            }.forEach { insertIndex ->
                val drawableRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerD
                val imageViewRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                replaceInstruction(
                    insertIndex,
                    "invoke-static { v$imageViewRegister, v$drawableRegister }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->" +
                            "setDoodleDrawable(Landroid/widget/ImageView;Landroid/graphics/drawable/Drawable;)V"
                )
            }
        }

        // endregion


        // region hide view count

        HideViewCountFingerprint.method.apply {
            val startIndex = HideViewCountFingerprint.instructionMatches.first().index
            var returnStringRegister = getInstruction<OneRegisterInstruction>(startIndex).registerA

            // Find the instruction where the text dimension is retrieved.
            val applyDimensionIndex = indexOfFirstInstructionReversedOrThrow {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_STATIC &&
                        reference?.definingClass == "Landroid/util/TypedValue;" &&
                        reference.returnType == "F" &&
                        reference.name == "applyDimension" &&
                        reference.parameterTypes == listOf("I", "F", "Landroid/util/DisplayMetrics;")
            }

            // A float value is passed which is used to determine subtitle text size.
            val floatDimensionRegister = getInstruction<OneRegisterInstruction>(
                applyDimensionIndex + 1
            ).registerA

            addInstructions(
                applyDimensionIndex - 1,
                """
                    invoke-static { v$returnStringRegister, v$floatDimensionRegister }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->modifyFeedSubtitleSpan(Landroid/text/SpannableString;F)Landroid/text/SpannableString;
                    move-result-object v$returnStringRegister
                """
            )
        }

        // endregion

        // region hide filter bar

        /**
         * Patch a [Method] with a given [instructions].
         *
         * @param RegisterInstruction The type of instruction to get the register from.
         * @param insertIndexOffset The offset to add to the end index of the [Match.patternMatch].
         * @param hookRegisterOffset The offset to add to the register of the hook.
         * @param instructions The instructions to add with the register as a parameter.
         */
        fun <RegisterInstruction : OneRegisterInstruction> Fingerprint.patch(
            insertIndexOffset: Int = 0,
            hookRegisterOffset: Int = 0,
            instructions: (Int) -> String,
        ) = method.apply {
            val endIndex = instructionMatches.last().index
            val insertIndex = endIndex + insertIndexOffset
            val register = getInstruction<RegisterInstruction>(endIndex + hookRegisterOffset).registerA

            addInstructions(insertIndex, instructions(register))
        }

        FilterBarHeightFingerprint.patch<TwoRegisterInstruction> { register ->
            """
                invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->hideInFeed(I)I
                move-result v$register
            """
        }

        SearchResultsChipBarFingerprint.patch<OneRegisterInstruction>(-1, -2) { register ->
            """
                invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->hideInSearch(I)I
                move-result v$register
            """
        }

        RelatedChipCloudFingerprint.patch<OneRegisterInstruction>(1) { register ->
            "invoke-static { v$register }, " +
                "$LAYOUT_COMPONENTS_FILTER_CLASS_DESCRIPTOR->hideInRelatedVideos(Landroid/view/View;)V"
        }
    }
}
