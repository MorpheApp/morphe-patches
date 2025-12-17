package app.morphe.patches.youtube.layout.hide.shorts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_41_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_07_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_45_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.ConversionContextFingerprintToString
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.forEachLiteralValueInstruction
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.removeFromParent
import app.morphe.util.returnLate
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val hideShortsAppShortcutOption = booleanOption(
    key = "hideShortsAppShortcut",
    default = false,
    title = "Hide Shorts app shortcut",
    description = "Permanently hides the shortcut to open Shorts when long pressing the app icon in your launcher.",
)

internal val hideShortsWidgetOption = booleanOption(
    key = "hideShortsWidget",
    default = false,
    title = "Hide Shorts widget",
    description = "Permanently hides the launcher widget Shorts button.",
)

private val hideShortsComponentsResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        resourceMappingPatch,
        versionCheckPatch,
    )

    execute {
        val hideShortsAppShortcut by hideShortsAppShortcutOption
        val hideShortsWidget by hideShortsWidgetOption

        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_hide_shorts_home"),
            SwitchPreference("morphe_hide_shorts_search"),
            SwitchPreference("morphe_hide_shorts_subscriptions"),
            SwitchPreference("morphe_hide_shorts_history"),

            PreferenceScreenPreference(
                key = "morphe_shorts_player_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    // Shorts player components.
                    // Ideally each group should be ordered similar to how they appear in the UI

                    // Vertical row of buttons on right side of the screen.
                    // Like fountain may no longer be used by YT anymore.
                    //SwitchPreference("morphe_hide_shorts_like_fountain"),
                    SwitchPreference("morphe_hide_shorts_like_button"),
                    SwitchPreference("morphe_hide_shorts_dislike_button"),
                    SwitchPreference("morphe_hide_shorts_comments_button"),
                    SwitchPreference("morphe_hide_shorts_share_button"),
                    SwitchPreference("morphe_hide_shorts_remix_button"),
                    SwitchPreference("morphe_hide_shorts_sound_button"),

                    // Upper and middle area of the player.
                    SwitchPreference("morphe_hide_shorts_join_button"),
                    SwitchPreference("morphe_hide_shorts_subscribe_button"),
                    SwitchPreference("morphe_hide_shorts_paused_overlay_buttons"),

                    // Suggested actions.
                    SwitchPreference("morphe_hide_shorts_preview_comment"),
                    SwitchPreference("morphe_hide_shorts_save_sound_button"),
                    SwitchPreference("morphe_hide_shorts_use_sound_button"),
                    SwitchPreference("morphe_hide_shorts_use_template_button"),
                    SwitchPreference("morphe_hide_shorts_upcoming_button"),
                    SwitchPreference("morphe_hide_shorts_effect_button"),
                    SwitchPreference("morphe_hide_shorts_green_screen_button"),
                    SwitchPreference("morphe_hide_shorts_hashtag_button"),
                    SwitchPreference("morphe_hide_shorts_new_posts_button"),
                    SwitchPreference("morphe_hide_shorts_shop_button"),
                    SwitchPreference("morphe_hide_shorts_tagged_products"),
                    SwitchPreference("morphe_hide_shorts_search_suggestions"),
                    SwitchPreference("morphe_hide_shorts_super_thanks_button"),
                    SwitchPreference("morphe_hide_shorts_stickers"),

                    // Bottom of the screen.
                    SwitchPreference("morphe_hide_shorts_location_label"),
                    SwitchPreference("morphe_hide_shorts_channel_bar"),
                    SwitchPreference("morphe_hide_shorts_info_panel"),
                    SwitchPreference("morphe_hide_shorts_full_video_link_label"),
                    SwitchPreference("morphe_hide_shorts_video_title"),
                    SwitchPreference("morphe_hide_shorts_sound_metadata_label"),
                    SwitchPreference("morphe_hide_shorts_navigation_bar"),
                ),
            )
        )

        // Verify the file has the expected node, even if the patch option is off.
        document("res/xml/main_shortcuts.xml").use { document ->
            val shortsItem = document.childNodes.findElementByAttributeValueOrThrow(
                "android:shortcutId",
                "shorts-shortcut",
            )

            if (hideShortsAppShortcut == true) {
                shortsItem.removeFromParent()
            }
        }

        document("res/layout/appwidget_two_rows.xml").use { document ->
            val shortsItem = document.childNodes.findElementByAttributeValueOrThrow(
                "android:id",
                "@id/button_shorts_container",
            )

            if (hideShortsWidget == true) {
                shortsItem.removeFromParent()
            }
        }
    }
}

private const val FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/components/ShortsFilter;"

@Suppress("unused")
val hideShortsComponentsPatch = bytecodePatch(
    name = "Hide Shorts components",
    description = "Adds options to hide components related to Shorts. " +
            "Patching version 20.21.37 or lower can hide more Shorts player button types."
) {
    dependsOn(
        sharedExtensionPatch,
        lithoFilterPatch,
        hideShortsComponentsResourcePatch,
        resourceMappingPatch,
        navigationBarHookPatch,
        versionCheckPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    hideShortsAppShortcutOption()
    hideShortsWidgetOption()

    execute {
        addLithoFilter(FILTER_CLASS_DESCRIPTOR)

        // region Hide sound button.

        forEachLiteralValueInstruction(
            getResourceId(ResourceType.DIMEN, "reel_player_right_pivot_v2_size")
        ) { literalInstructionIndex ->
            val targetIndex = indexOfFirstInstructionOrThrow(literalInstructionIndex) {
                getReference<MethodReference>()?.name == "getDimensionPixelSize"
            } + 1

            val sizeRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

            addInstructions(
                targetIndex + 1,
                """
                    invoke-static { v$sizeRegister }, $FILTER_CLASS_DESCRIPTOR->getSoundButtonSize(I)I
                    move-result v$sizeRegister
                """
            )
        }

        // endregion

        // region Hide action buttons.

        if (is_20_22_or_greater) {
            TreeNodeResultListFingerprint.match(
                ComponentContextParserFingerprint.originalClassDef,
            ).method.apply {
                val conversionContextPathBuilderField = ConversionContextFingerprintToString.originalClassDef
                    .fields.single { field -> field.type == "Ljava/lang/StringBuilder;" }

                val insertIndex = implementation!!.instructions.lastIndex
                val listRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                val registerProvider = getFreeRegisterProvider(insertIndex)
                val freeRegister = registerProvider.getFreeRegister()
                val pathRegister = registerProvider.getFreeRegister()

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        move-object/from16 v$freeRegister, p2
                        
                        # 20.41 field is the abstract superclass.
                        # Verify it's the expected subclass just in case.
                        instance-of v$pathRegister, v$freeRegister, ${ConversionContextFingerprintToString.classDef.type}
                        if-eqz v$pathRegister, :ignore
                        
                        iget-object v$pathRegister, v$freeRegister, $conversionContextPathBuilderField
                        invoke-static { v$pathRegister, v$listRegister }, $FILTER_CLASS_DESCRIPTOR->hideActionButtons(Ljava/lang/StringBuilder;Ljava/util/List;)V
                        :ignore
                        nop
                    """
                )
            }
        }

        // endregion

        // region Hide the navigation bar.

        // Hook to get the pivotBar view.
        SetPivotBarVisibilityFingerprint.match(
            SetPivotBarVisibilityParentFingerprint.originalClassDef,
        ).let { result ->
            result.method.apply {
                val insertIndex = result.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(insertIndex - 1).registerA
                addInstruction(
                    insertIndex,
                    "invoke-static {v$viewRegister}," +
                        " $FILTER_CLASS_DESCRIPTOR->setNavigationBar(Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;)V",
                )
            }
        }

        // Hook to hide the shared navigation bar when the Shorts player is opened.
        RenderBottomNavigationBarFingerprint.match(
            (if (is_20_45_or_greater) {
                RenderBottomNavigationBarParentFingerprint
            } else if (is_19_41_or_greater) {
                RenderBottomNavigationBarLegacy1941ParentFingerprint
            } else {
                LegacyRenderBottomNavigationBarLegacyParentFingerprint
            }).originalClassDef
        ).method.addInstruction(
            0,
            "invoke-static { p1 }, $FILTER_CLASS_DESCRIPTOR->hideNavigationBar(Ljava/lang/String;)V",
        )

        // Hide the bottom bar container of the Shorts player.
        ShortsBottomBarContainerFingerprint.let {
            it.method.apply {
                val targetIndex = it.instructionMatches.last().index
                val heightRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                addInstructions(
                    targetIndex + 1,
                    """
                        invoke-static { v$heightRegister }, $FILTER_CLASS_DESCRIPTOR->getNavigationBarHeight(I)I
                        move-result v$heightRegister
                    """
                )
            }
        }

        // endregion


        // region Disable experimental Shorts flags.

        // Flags might be present in earlier targets, but they are not found in 19.47.53.
        // If these flags are forced on, the experimental layout is still not used and
        // it appears the features requires additional server side data to fully use.
        if (is_20_07_or_greater) {
            // Experimental Shorts player uses Android native buttons and not Litho,
            // and the layout is provided by the server.
            //
            // Since the buttons are native components and not Litho, it should be possible to
            // fix the RYD Shorts loading delay by asynchronously loading RYD and updating
            // the button text after RYD has loaded.
            ShortsExperimentalPlayerFeatureFlagFingerprint.method.returnLate(false)

            // Experimental UI renderer must also be disabled since it requires the
            // experimental Shorts player.  If this is enabled but Shorts player
            // is disabled then the app crashes when the Shorts player is opened.
            RenderNextUIFeatureFlagFingerprint.method.returnLate(false)
        }

        // endregion
    }
}
