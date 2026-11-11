/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.general

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.ad.injectHideViewCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val COMMENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/CommentsFilter;"
private const val CUSTOM_FILTER =
    "Lapp/morphe/extension/music/patches/components/CustomFilter;"
private const val LAYOUT_COMPONENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/LayoutComponentsFilter;"

@Suppress("unused")
val hideLayoutComponentsPatch = patch(
    name = "Hide layout components",
    description = "Adds options to hide general layout components."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_music_hide_podcast_episode_download_button", summary = true),
            SwitchPreference("morphe_music_hide_explore_shelf"),
            SwitchPreference("morphe_music_hide_grid_shelves"),
            SwitchPreference("morphe_music_hide_filter_bar"),
            SwitchPreference("morphe_music_hide_horizontal_shelves"),
            SwitchPreference("morphe_music_hide_list_shelves"),
            SwitchPreference("morphe_music_hide_new_from_shelf"),
            SwitchPreference("morphe_music_hide_playlist_shelves"),
            SwitchPreference("morphe_music_hide_speed_dial_shelf"),
            SwitchPreference("morphe_music_hide_you_may_also_like_section"),
            SwitchPreference("morphe_music_hide_your_downloads_shelf")
        )

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_custom_filter_screen",
                titleKey = "morphe_custom_filter_screen_title",
                summaryKey = "morphe_custom_filter_screen_summary",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        key = "morphe_music_custom_filter",
                        titleKey = "morphe_custom_filter_title"
                    ),
                    TextPreference(
                        key = "morphe_music_custom_filter_strings",
                        titleKey = "morphe_custom_filter_screen_title",
                        summaryKey = "morphe_custom_filter_strings_summary",
                        inputType = InputType.TEXT_MULTI_LINE
                    )
                )
            )
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_comments_screen",
                titleKey = "morphe_music_comments_screen_title",
                summaryKey = "morphe_music_comments_screen_summary",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_comments_community_guidelines"),
                    SwitchPreference("morphe_music_hide_comments_context"),
                    SwitchPreference("morphe_music_hide_comments_emoji_button"),
                    SwitchPreference("morphe_music_hide_comments_gif_button"),
                    SwitchPreference("morphe_music_hide_comments_gifs_and_images", summary = true),
                    SwitchPreference("morphe_music_hide_comments_info_button"),
                    SwitchPreference("morphe_music_hide_comments_timestamp_button")
                )
            ),
            SwitchPreference("morphe_music_hide_audio_video_toggle"),
            PreferenceCategory(
                titleKey = "morphe_music_hide_lyrics_panel_category_title",
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_lyrics_share_button"),
                    SwitchPreference("morphe_music_hide_lyrics_translate_button")
                )
            ),
            SwitchPreference("morphe_music_hide_repeat_button"),
            SwitchPreference("morphe_music_hide_shuffle_button"),
        )

        addLithoFilter(COMMENTS_FILTER)
        addLithoFilter(CUSTOM_FILTER)
        addLithoFilter(LAYOUT_COMPONENTS_FILTER)

        // region hide audio / video toggle
        AudioVideoSwitchPillContainerFingerprint.matchAll().forEach { match ->
            match.method.injectHideViewCall(
                match.instructionMatches.last().index,
                LAYOUT_COMPONENTS_FILTER,
                "hideAudioVideoToggle"
            )
        }
        // endregion

        // region hide filter bar
        ChipCloudFingerprint.method.apply {
            val targetIndex = ChipCloudFingerprint.instructionMatches.last().index
            val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

            addInstruction(
                targetIndex + 1,
                "invoke-static { v$targetRegister }, $LAYOUT_COMPONENTS_FILTER->hideFilterBar(Landroid/view/View;)V"
            )
        }
        // endregion

        // region hide comments info button
        InformationButtonFingerprint.let {
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

        // region hide repeat button
        val repeatFingerprints = listOf(
            OverlayQueueLoopButtonFingerprint,
            PlaybackQueueLoopButtonFingerprint
        )

        repeatFingerprints.forEach { fingerprint ->
            fingerprint.matchAllOrNull()?.forEach { match ->
                match.method.injectHideViewCall(
                    match.instructionMatches.last().index,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideRepeatButton"
                )
            }
        }
        // endregion

        // region hide shuffle button
        val shuffleFingerprints = listOf(
            OverlayQueueShuffleButtonFingerprint,
            PlaybackQueueShuffleButtonFingerprint
        )

        shuffleFingerprints.forEach { fingerprint ->
            fingerprint.matchAllOrNull()?.forEach { match ->
                match.method.injectHideViewCall(
                    match.instructionMatches.last().index,
                    LAYOUT_COMPONENTS_FILTER,
                    "hideShuffleButton"
                )
            }
        }
        // endregion

        // region hide 'You may also like' section in search
        // Each shelf item holds one renderer: a search history entry, a suggested search,
        // or another renderer used outside the search page.
        val shelfItemsMatches = MusicShelfItemsFingerprint.instructionMatches
        val shelfItemsField = shelfItemsMatches[0].fieldReference
        val searchHistoryField = shelfItemsMatches[2].fieldReference
        val suggestedSearchField = shelfItemsMatches[4].fieldReference
        val shelfType = shelfItemsField.definingClass
        val shelfItemType = searchHistoryField.definingClass
        val protoListType = shelfItemsField.type
        val protoListMutableCopyMethod = Fingerprint(
            definingClass = protoListType,
            returnType = protoListType,
            parameters = listOf("I")
        ).method

        SearchSuggestionsRenderFingerprint.let {
            it.method.apply {
                val sectionListWrapperInit = it.instructionMatches[1]
                val sectionListWrapperInitReference = sectionListWrapperInit.getInstruction<ReferenceInstruction>()
                    .reference as MethodReference
                val sectionListType = sectionListWrapperInitReference.parameterTypes.first().toString()
                val sectionListRegister = sectionListWrapperInit.instruction.registersUsed[1]

                // The section list wrapper converts a section list item to its renderer.
                val sectionListItemType = Fingerprint(
                    definingClass = sectionListWrapperInitReference.definingClass,
                    returnType = "Lj$/util/Optional;",
                    parameters = listOf("L")
                ).method.parameterTypes.first().toString()

                val sectionListItemShelfField = classDefBy(sectionListItemType).fields.first { field ->
                    field.type == shelfType
                }
                val sectionListContentsFields = classDefBy(sectionListType).fields.filter { field ->
                    !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == protoListType
                }
                val recyclerViewField = it.classDef.fields.first { field ->
                    field.type == "Landroid/support/v7/widget/RecyclerView;"
                }

                // Returns the list without the shelves that only hold suggested searches.
                val removeShelvesMethod = ImmutableMethod(
                    definingClass,
                    "patch_removeSuggestedSearchShelves",
                    listOf(ImmutableMethodParameter(protoListType, null, null)),
                    protoListType,
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(10),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-interface { p0 }, Ljava/util/List;->size()I
                            move-result v0
                            new-instance v1, Ljava/util/ArrayList;
                            invoke-direct { v1, v0 }, Ljava/util/ArrayList;-><init>(I)V
                            const/4 v2, 0x0
                            const/4 v3, 0x0

                            :next_section
                            if-ge v3, v0, :sections_done
                            invoke-interface { p0, v3 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                            move-result-object v4
                            add-int/lit8 v3, v3, 0x1
                            instance-of v5, v4, $sectionListItemType
                            if-eqz v5, :keep_section
                            move-object v5, v4
                            check-cast v5, $sectionListItemType
                            iget-object v5, v5, $sectionListItemShelfField
                            if-eqz v5, :keep_section
                            iget-object v5, v5, $shelfItemsField
                            invoke-interface { v5 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                            move-result-object v5
                            const/4 v6, 0x0

                            :next_shelf_item
                            invoke-interface { v5 }, Ljava/util/Iterator;->hasNext()Z
                            move-result v7
                            if-eqz v7, :shelf_items_done
                            invoke-interface { v5 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                            move-result-object v7
                            check-cast v7, $shelfItemType
                            iget-object v8, v7, $searchHistoryField
                            if-nez v8, :keep_section
                            iget-object v8, v7, $suggestedSearchField
                            if-eqz v8, :keep_section
                            const/4 v6, 0x1
                            goto :next_shelf_item

                            :shelf_items_done
                            if-eqz v6, :keep_section
                            const/4 v2, 0x1
                            goto :next_section

                            :keep_section
                            invoke-virtual { v1, v4 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            goto :next_section

                            :sections_done
                            if-eqz v2, :unchanged
                            invoke-interface { p0, v0 }, $protoListMutableCopyMethod
                            move-result-object v0
                            invoke-interface { v0 }, Ljava/util/List;->clear()V
                            invoke-interface { v0, v1 }, Ljava/util/List;->addAll(Ljava/util/Collection;)Z
                            return-object v0

                            :unchanged
                            return-object p0
                        """
                    )
                }.also { method -> it.classDef.methods.add(method) }

                // Removes the 'You may also like' shelf from the section list, if enabled.
                val hideSectionMethod = ImmutableMethod(
                    definingClass,
                    "patch_hideYouMayAlsoLikeSection",
                    listOf(
                        ImmutableMethodParameter(definingClass, null, null),
                        ImmutableMethodParameter(sectionListType, null, null)
                    ),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            iget-object v0, p0, $recyclerViewField
                            invoke-static { v0 }, $LAYOUT_COMPONENTS_FILTER->hideYouMayAlsoLikeSection(Landroid/view/View;)Z
                            move-result v0
                            if-eqz v0, :exit
                        """ + sectionListContentsFields.joinToString("") { field ->
                            """
                            iget-object v0, p1, $field
                            invoke-static { v0 }, $removeShelvesMethod
                            move-result-object v0
                            iput-object v0, p1, $field
                            """
                        } + """
                            :exit
                            return-void
                        """
                    )
                }.also { method -> it.classDef.methods.add(method) }

                addInstruction(
                    it.instructionMatches[0].index,
                    "invoke-static { p0, v$sectionListRegister }, $hideSectionMethod"
                )
            }
        }
        // endregion
    }
}
