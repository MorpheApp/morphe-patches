/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.general

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.hide.shelves.hideHorizontalShelvesPatch
import app.morphe.patches.youtube.layout.hide.updatescreen.hideUpdateScreenPatch
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.litho.node.hookTreeNodeResult
import app.morphe.patches.youtube.misc.litho.node.treeNodeElementHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_21_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_11_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.proto.hookElement
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.injectHideViewCall
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val LAYOUT_COMPONENTS_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/LayoutComponentsFilter;"
private const val DESCRIPTION_COMPONENTS_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/DescriptionComponentsFilter;"
private const val COMMENTS_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/CommentsFilter;"
private const val CUSTOM_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/CustomFilter;"
private const val KEYWORD_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/KeywordContentFilter;"

val hideLayoutComponentsPatch = bytecodePatch(
    name = "Hide layout components",
    description = "Adds options to hide general layout components.",

) {
    dependsOn(
        lithoFilterPatch,
        settingsPatch,
        engagementPanelHookPatch,
        navigationBarHookPatch,
        versionCheckPatch,
        resourceMappingPatch,
        hideHorizontalShelvesPatch,
        hideUpdateScreenPatch,
        elementProtoParserHookPatch,
        fixProtoLibraryPatch,
        treeNodeElementHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.ADS.addPreferences(
            // Uses horizontal shelf and a buffer, which requires managing in a single place in the code
            // to ensure the generic "hide horizontal shelves" doesn't hide when it should show.
            SwitchPreference("morphe_hide_creator_store_shelf")
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_description_components_screen",
                preferences = setOf(
                    SwitchPreference("morphe_hide_ai_generated_video_summary_section"),
                    SwitchPreference("morphe_hide_ask_section"),
                    SwitchPreference("morphe_hide_attributes_section"),
                    SwitchPreference("morphe_hide_chapters_section"),
                    SwitchPreference("morphe_hide_course_progress_section"),
                    SwitchPreference("morphe_hide_explore_section"),
                    SwitchPreference("morphe_hide_explore_course_section"),
                    SwitchPreference("morphe_hide_explore_podcast_section"),
                    SwitchPreference("morphe_hide_featured_links_section"),
                    SwitchPreference("morphe_hide_featured_places_section"),
                    SwitchPreference("morphe_hide_featured_videos_section"),
                    SwitchPreference("morphe_hide_gaming_section"),
                    SwitchPreference("morphe_hide_how_this_was_made_section"),
                    SwitchPreference("morphe_hide_hype_points"),
                    SwitchPreference("morphe_hide_info_cards_section"),
                    SwitchPreference("morphe_hide_key_concepts_section"),
                    SwitchPreference("morphe_hide_music_section"),
                    SwitchPreference("morphe_hide_quizzes_section"),
                    SwitchPreference("morphe_hide_subscribe_button"),
                    SwitchPreference("morphe_hide_transcript_section")
                ),
            ),
            PreferenceScreenPreference(
                "morphe_comments_screen",
                preferences = setOf(
                    PreferenceCategory(
                        titleKey = null,
                        sorting = Sorting.UNSORTED,
                        tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                        preferences = setOf(
                            SwitchPreference("morphe_hide_comments_carousel"),
                            TextPreference(
                                "morphe_hide_comments_carousel_filter_strings",
                                inputType = InputType.TEXT_MULTI_LINE
                            ),
                        )
                    ),
                    SwitchPreference("morphe_hide_comments_ai_chat_summary"),
                    SwitchPreference("morphe_hide_comments_channel_guidelines"),
                    SwitchPreference("morphe_hide_comments_prompts"),
                    SwitchPreference("morphe_hide_comments_by_members_header"),
                    SwitchPreference("morphe_hide_comments_section"),
                    SwitchPreference("morphe_hide_comments_section_in_home_feed"),
                    SwitchPreference("morphe_hide_comments_community_guidelines"),
                    SwitchPreference("morphe_hide_comments_create_a_short_button"),
                    SwitchPreference("morphe_hide_comments_emoji_and_timestamp_buttons"),
                    SwitchPreference("morphe_hide_comments_info_button"),
                    SwitchPreference("morphe_hide_comments_preview_comment"),
                    SwitchPreference("morphe_hide_comments_thanks_button"),
                    SwitchPreference("morphe_sanitize_comments_category_bar"),
                ),
                sorting = Sorting.UNSORTED,
            ),
            SwitchPreference("morphe_hide_channel_bar"),
            SwitchPreference("morphe_hide_channel_watermark"),
            SwitchPreference("morphe_hide_crowdfunding_box"),
            SwitchPreference("morphe_hide_emergency_box"),
            SwitchPreference("morphe_hide_info_panels"),
            SwitchPreference("morphe_hide_join_membership_button"),
            SwitchPreference("morphe_hide_live_chat_donators_bar"),
            SwitchPreference("morphe_hide_live_chat_replay_button"),
            SwitchPreference("morphe_hide_medical_panels"),
            SwitchPreference("morphe_hide_subscribers_community_guidelines"),
            SwitchPreference("morphe_hide_timed_reactions"),
            SwitchPreference("morphe_hide_video_title"),
        )

        PreferenceScreen.FEED.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_keyword_content_screen",
                sorting = Sorting.UNSORTED,
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
                        tag = "app.morphe.extension.youtube.settings.preference.HTMLPreference",
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
                    PreferenceCategory(
                        titleKey = null,
                        sorting = Sorting.UNSORTED,
                        tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                        preferences = setOf(
                            SwitchPreference("morphe_hide_channel_tab"),
                            TextPreference(
                                "morphe_hide_channel_tab_filter_strings",
                                inputType = InputType.TEXT_MULTI_LINE
                            ),
                        )
                    ),
                    SwitchPreference("morphe_hide_community_button"),
                    SwitchPreference("morphe_hide_join_button"),
                    SwitchPreference("morphe_hide_links_preview"),
                    SwitchPreference("morphe_hide_members_shelf"),
                    SwitchPreference("morphe_hide_posts_shelf"),
                    SwitchPreference("morphe_hide_store_button"),
                    SwitchPreference("morphe_hide_subscribe_button_in_channel_page"),
                ),
            ),
            SwitchPreference("morphe_hide_album_cards"),
            SwitchPreference("morphe_hide_artist_cards"),
            SwitchPreference("morphe_hide_community_posts"),
            SwitchPreference("morphe_hide_compact_banner"),
            if (is_20_26_or_greater) {
                ListPreference("morphe_hide_expandable_card")
            } else {
                ListPreference(
                    key = "morphe_hide_expandable_card",
                    entriesKey = "morphe_hide_expandable_card_legacy_entries",
                    entryValuesKey = "morphe_hide_expandable_card_legacy_entry_values"
                )
            },
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference("morphe_hide_feed_flyout_menu"),
                    TextPreference(
                        "morphe_hide_feed_flyout_menu_filter_strings",
                        inputType = InputType.TEXT_MULTI_LINE
                    ),
                )
            ),
            SwitchPreference("morphe_hide_floating_microphone_button"),
            SwitchPreference(
                key = "morphe_hide_horizontal_shelves",
                tag = "app.morphe.extension.shared.settings.preference.BulletPointSwitchPreference"
            ),
            SwitchPreference("morphe_hide_image_shelf"),
            SwitchPreference("morphe_hide_latest_videos_button"),
            SwitchPreference("morphe_hide_mix_playlists"),
            SwitchPreference("morphe_hide_movies_section"),
            SwitchPreference("morphe_hide_notify_me_button"),
            SwitchPreference("morphe_hide_playables"),
            SwitchPreference("morphe_hide_search_term_thumbnails"),
            SwitchPreference("morphe_hide_show_more_button"),
            SwitchPreference("morphe_hide_subscribed_channels_bar"),
            SwitchPreference("morphe_hide_surveys"),
            SwitchPreference("morphe_hide_ticket_shelf"),
            SwitchPreference("morphe_hide_upload_time"),
            SwitchPreference("morphe_hide_video_recommendation_labels"),
            SwitchPreference("morphe_hide_view_count"),
            SwitchPreference("morphe_hide_web_search_results"),
            SwitchPreference("morphe_hide_youtube_doodles"),
        )

        if (is_20_21_or_greater) {
            PreferenceScreen.FEED.addPreferences(
                SwitchPreference("morphe_hide_you_may_like_section")
            )
        }

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_custom_filter_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_custom_filter"),
                    TextPreference("morphe_custom_filter_strings", inputType = InputType.TEXT_MULTI_LINE),
                ),
            ),
        )

        addLithoFilter(LAYOUT_COMPONENTS_FILTER)
        addLithoFilter(DESCRIPTION_COMPONENTS_FILTER)
        addLithoFilter(COMMENTS_FILTER)
        addLithoFilter(KEYWORD_FILTER)
        addLithoFilter(CUSTOM_FILTER)
        hookTreeNodeResult("$COMMENTS_FILTER->sanitizeCommentsCategoryBar")

        // region hide mix playlists

        ParseElementFromBufferFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index

                val byteArrayParameter = "p3"
                val returnEmptyComponentIndex = it.instructionMatches[4].index
                val returnEmptyComponentInstruction = getInstruction(returnEmptyComponentIndex)
                val returnEmptyComponentRegister = (returnEmptyComponentInstruction as FiveRegisterInstruction).registerC
                val freeRegister = findFreeRegister(insertIndex, returnEmptyComponentRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-static { $byteArrayParameter }, $LAYOUT_COMPONENTS_FILTER->filterMixPlaylists([B)Z
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
        }

        // endregion

        // region hide watermark (legacy code for old versions of YouTube)

        ShowWatermarkFingerprint.method.apply {
            val index = implementation!!.instructions.size - 5

            removeInstruction(index)
            addInstructions(
                index,
                """
                    invoke-static {}, $LAYOUT_COMPONENTS_FILTER->showWatermark()Z
                    move-result p2
                """,
            )
        }

        // endregion

        // region hide show more button

        val (textViewField, buttonContainerField) = with (HideShowMoreButtonSetViewFingerprint) {
            val textViewIndex = instructionMatches[1].index
            val buttonContainerIndex = instructionMatches[3].index

            Pair(
                method.getInstruction<ReferenceInstruction>(textViewIndex).reference,
                method.getInstruction<ReferenceInstruction>(buttonContainerIndex).reference
            )
        }

        val parentViewMethod = HideShowMoreButtonGetParentViewFingerprint.method

        HideShowMoreButtonFingerprint.clearMatch()
        HideShowMoreButtonFingerprint.let {
            it.method.apply {
                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_hideShowMoreButton",
                    listOf(),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(7),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            move-object/from16 v0, p0
                            invoke-virtual { v0 }, $parentViewMethod
                            move-result-object v1
                            iget-object v2, v0, $buttonContainerField
                            iget-object v3, v0, $textViewField
                            invoke-static { v1, v2, v3 }, $LAYOUT_COMPONENTS_FILTER->hideShowMoreButton(Landroid/view/View;Landroid/view/View;Landroid/widget/TextView;)V
                            return-void
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                    addInstruction(
                        index,
                        "invoke-direct/range { p0 .. p0 }, $helperMethod"
                    )
                }
            }
        }

        // endregion

        // region hide subscribed channels bar

        // Tablet
        val constructorFingerprint = if (is_20_21_or_greater)
            HideSubscribedChannelsBarConstructorFingerprint
        else HideSubscribedChannelsBarConstructorLegacyFingerprint

        constructorFingerprint.let {
            it.method.injectHideViewCall(
                it.instructionMatches[1].index,
                LAYOUT_COMPONENTS_FILTER,
                "hideSubscribedChannelsBar"
            )
        }

        // Phone (landscape mode)
        HideSubscribedChannelsBarLandscapeFingerprint.match(
            constructorFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER->hideSubscribedChannelsBar(I)I
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region hide album cards

        AlbumCardsFingerprint.let {
            it.method.injectHideViewCall(
                it.instructionMatches.last().index,
                LAYOUT_COMPONENTS_FILTER,
                "hideAlbumCard"
            )
        }

        // endregion

        // region hide comments carousel

        hookElement("$COMMENTS_FILTER->onCommentsLoaded([B)[B")

        // endregion

        // region hide comments info button

        EngagementPanelInformationButtonFingerprint.let {
            it.method.apply {
                val checkCastIndex = it.instructionMatches[1].index
                val viewRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$viewRegister }, $COMMENTS_FILTER->hideCommentsInfoButton(Landroid/view/View;)V"
                )
            }
        }

        //endregion

        // region hide crowdfunding box

        CrowdfundingBoxFingerprint.let {
            it.method.injectHideViewCall(
                it.instructionMatches.last().index,
                LAYOUT_COMPONENTS_FILTER,
                "hideCrowdfundingBox"
            )
        }

        // endregion

        // region hide live chat donators bar

        LiveChatDonatorsBarFingerprint.let {
            it.method.injectHideViewCall(
                it.instructionMatches.last().index,
                LAYOUT_COMPONENTS_FILTER,
                "hideLiveChatDonatorsBar"
            )
        }

        // endregion

        // region hide floating microphone

        val showFloatingMicrophoneButtonFingerprintMatch = if (is_21_11_or_greater)
            ShowFloatingMicrophoneButtonFingerprint
        else ShowFloatingMicrophoneButtonLegacyFingerprint

        showFloatingMicrophoneButtonFingerprintMatch.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER->hideFloatingMicrophoneButton(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region hide latest videos button

        listOf(
            LatestVideosContentPillFingerprint,
            LatestVideosBarFingerprint,
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.injectHideViewCall(
                    it.instructionMatches.last().index,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideLatestVideosButton"
                )
            }
        }

        // endregion

        // region hide YouTube Doodles

        YouTubeDoodlesImageViewFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.name == "setImageDrawable"
            }.forEach { insertIndex ->
                val drawableRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerD
                val imageViewRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                replaceInstruction(
                    insertIndex,
                    "invoke-static { v$imageViewRegister, v$drawableRegister }, $LAYOUT_COMPONENTS_FILTER->" +
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
                    invoke-static { v$returnStringRegister, v$floatDimensionRegister }, $LAYOUT_COMPONENTS_FILTER->modifyFeedSubtitleSpan(Landroid/text/SpannableString;F)Landroid/text/SpannableString;
                    move-result-object v$returnStringRegister
                """
            )
        }

        // endregion

        // region hide filter bar

        arrayOf(
            FilterBarHeightFingerprint to "hideInFeed",
            SearchResultsChipBarFingerprint to "hideInSearch",
            RelatedChipCloudFingerprint to "hideInRelatedVideos"
        ).forEach { (fingerprint, methodName) ->
            fingerprint.method.apply {
                val moveIndex = fingerprint.instructionMatches.last().index
                val sizeRegister = getInstruction<OneRegisterInstruction>(moveIndex).registerA

                addInstructions(
                    moveIndex + 1,
                    """
                        invoke-static { v$sizeRegister }, $LAYOUT_COMPONENTS_FILTER->$methodName(I)I
                        move-result v$sizeRegister
                    """
                )
            }
        }

        RelatedChipCloudFingerprint.let {
            it.clearMatch()
            it.method.apply {
                insertLiteralOverride(
                    it.instructionMatches[2].index,
                    "$LAYOUT_COMPONENTS_FILTER->hideInRelatedVideos(Z)Z"
                )
            }
        }

        RelatedChipCloudFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val viewIndex = it.instructionMatches[1].index
                val viewRegister = getInstruction<FiveRegisterInstruction>(viewIndex).registerC

                injectHideViewCall(
                    viewIndex,
                    viewRegister,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideInRelatedVideos"
                )
            }
        }

        // endregion

        // region hide you may like section

        if (is_20_21_or_greater) {
            val searchSuggestionEndpointField = SearchSuggestionEndpointFingerprint
                .instructionMatches.first().instruction.getReference<FieldReference>()!!
            val searchSuggestionEndpointClass = searchSuggestionEndpointField.definingClass

            SearchBoxTypingStringFingerprint.let {
                it.method.apply {
                    // A collection of search suggestions.
                    // This includes trending search (also known as 'You may like' section) and your search history.
                    val searchSuggestionCollectionField =
                        it.instructionMatches.first().instruction.getReference<FieldReference>()!!
                    val typedStringField =
                        it.instructionMatches[2].instruction.getReference<FieldReference>()!!

                    val helperMethod = ImmutableMethod(
                        definingClass,
                        "patch_setSearchSuggestions",
                        listOf(
                            ImmutableMethodParameter(
                                parameterTypes.first().toString(),
                                null,
                                null
                            )
                        ),
                        "V",
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                        annotations,
                        null,
                        MutableMethodImplementation(7),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                move-object/from16 v0, p1
                                iget-object v1, v0, $typedStringField
                                
                                # Check if the setting is enabled and if the typed string is empty.
                                invoke-static { v1 }, $LAYOUT_COMPONENTS_FILTER->hideYouMayLikeSection(Ljava/lang/String;)Z
                                move-result v1
                                
                                # If the setting is disabled or the typed string is not empty, do nothing.
                                if-eqz v1, :ignore

                                ## Get a collection of search suggestions.
                                iget-object v1, v0, $searchSuggestionCollectionField
                                
                                # Iterate through the collection and check if the search suggestion is the search history.
                                invoke-interface { v1 }, Ljava/util/Collection;->iterator()Ljava/util/Iterator;
                                move-result-object v2
                                
                                :loop
                                invoke-interface { v2 }, Ljava/util/Iterator;->hasNext()Z
                                move-result v3
                                if-eqz v3, :exit
                                invoke-interface { v2 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                                move-result-object v3
                                instance-of v4, v3, $searchSuggestionEndpointClass
                                if-eqz v4, :loop
                                check-cast v3, $searchSuggestionEndpointClass

                                # Each search suggestion has a command endpoint.
                                # If the search suggestion is the search history, the command includes the keyword '/delete'.
                                iget-object v4, v3, $searchSuggestionEndpointField
                                invoke-static { v3, v4 }, $LAYOUT_COMPONENTS_FILTER->isSearchHistory(Ljava/lang/Object;Ljava/lang/String;)Z
                                move-result v3
                                
                                # If this search suggestion is the search history, do nothing.
                                if-nez v3, :loop
                                
                                # If this search suggestion is not the search history, remove it from the search suggestions collection.
                                invoke-interface { v2 }, Ljava/util/Iterator;->remove()V
                                goto :loop

                                # Save the updated collection to a field.
                                :exit
                                iput-object v1, v0, $searchSuggestionCollectionField

                                :ignore
                                return-void
                            """
                        )
                    }

                    it.classDef.methods.add(helperMethod)

                    addInstruction(
                        0,
                        "invoke-direct/range { p0 .. p1 }, $helperMethod"
                    )
                }
            }
        }

        // endregion

        // region hide flyout menu items

        BottomSheetMenuItemBuilderFingerprint.matchAll().forEach { match ->
            match.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $LAYOUT_COMPONENTS_FILTER->hideFlyoutMenu(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                            move-result-object v$register      
                        """
                    )
                }
            }
        }

        ContextualMenuItemBuilderFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val targetInstruction = getInstruction<FiveRegisterInstruction>(index)

                addInstruction(
                    index + 1,
                    "invoke-static { v${targetInstruction.registerC}, v${targetInstruction.registerD} }, " +
                            "$LAYOUT_COMPONENTS_FILTER->hideFlyoutMenu(Landroid/widget/TextView;Ljava/lang/CharSequence;)V"
                )
            }
        }

        // endregion

        // region hide channel tab

        val channelTabBuilderMethod = ChannelTabBuilderFingerprint.method
        ChannelTabRendererFingerprint.let { match ->
            match.method.apply {
                val iteratorIndex = indexOfFirstInstructionReversedOrThrow {
                    getReference<MethodReference>()?.name == "hasNext"
                }

                val iteratorRegister = getInstruction<FiveRegisterInstruction>(iteratorIndex).registerC
                val targetIndex = indexOfFirstInstructionReversedOrThrow {
                    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference

                    opcode == Opcode.INVOKE_INTERFACE &&
                            reference?.returnType == channelTabBuilderMethod.returnType &&
                            reference.parameterTypes == channelTabBuilderMethod.parameterTypes
                }

                val objectIndex = indexOfFirstInstructionReversedOrThrow(targetIndex, Opcode.IGET_OBJECT)
                val objectInstruction = getInstruction<TwoRegisterInstruction>(objectIndex)
                val objectReference = getInstruction<ReferenceInstruction>(objectIndex).reference

                addInstructionsWithLabels(
                    objectIndex + 1,
                    """
                        invoke-static { v${objectInstruction.registerA} }, $LAYOUT_COMPONENTS_FILTER->hideChannelTab(Ljava/lang/String;)Z
                        move-result v${objectInstruction.registerA}
                        if-eqz v${objectInstruction.registerA}, :ignore
                        invoke-interface { v$iteratorRegister }, Ljava/util/Iterator;->remove()V
                        goto :next_iterator
                        :ignore
                        iget-object v${objectInstruction.registerA}, v${objectInstruction.registerB}, $objectReference
                    """,
                    ExternalLabel("next_iterator", getInstruction(iteratorIndex))
                )
            }
        }

        // endregion

        // region hide search term thumbnails

        CreateSearchSuggestionsFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches[2].index - 1
                val freeRegister = findFreeRegister(insertIndex)
                val jumpIndex = it.instructionMatches.last().index

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { }, $LAYOUT_COMPONENTS_FILTER->hideSearchTermThumbnails()Z
                        move-result v$freeRegister
                        
                        if-nez v$freeRegister, :hidden
                    """,
                    ExternalLabel("hidden", getInstruction(jumpIndex))
                )
            }
        }
    }
}
