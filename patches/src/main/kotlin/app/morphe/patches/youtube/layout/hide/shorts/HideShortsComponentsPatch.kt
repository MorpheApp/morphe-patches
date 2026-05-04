/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.shorts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.litho.observer.layoutReloadObserverPatch
import app.morphe.patches.youtube.misc.navigation.addBottomBarContainerHook
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.forEachLiteralValueInstruction
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
            SwitchPreference("morphe_hide_shorts_channel"),
            SwitchPreference("morphe_hide_shorts_home"),
            SwitchPreference("morphe_hide_shorts_search"),
            SwitchPreference("morphe_hide_shorts_subscriptions"),
            SwitchPreference("morphe_hide_shorts_video_description"),
            SwitchPreference("morphe_hide_shorts_history"),
            SwitchPreference("morphe_disable_shorts_double_tap_to_like"),

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
                    SwitchPreference("morphe_hide_shorts_live_preview"),
                    SwitchPreference("morphe_hide_shorts_new_posts_button"),
                    SwitchPreference("morphe_hide_shorts_shop_button"),
                    SwitchPreference("morphe_hide_shorts_tagged_products"),
                    SwitchPreference("morphe_hide_shorts_search_suggestions"),
                    SwitchPreference("morphe_hide_shorts_super_thanks_button"),
                    SwitchPreference("morphe_hide_shorts_stickers"),

                    // Bottom of the screen.
                    SwitchPreference("morphe_hide_shorts_ai_button"),
                    SwitchPreference("morphe_hide_shorts_auto_dubbed_label"),
                    SwitchPreference("morphe_hide_shorts_location_label"),
                    SwitchPreference("morphe_hide_shorts_channel_bar"),
                    SwitchPreference("morphe_hide_shorts_info_panel"),
                    SwitchPreference("morphe_hide_shorts_full_video_link_label"),
                    SwitchPreference("morphe_hide_shorts_video_title"),
                    SwitchPreference("morphe_hide_shorts_sound_metadata_label"),
                    SwitchPreference("morphe_hide_shorts_navigation_bar"),
                )
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

private const val EXTENSION_FILTER = "Lapp/morphe/extension/youtube/patches/components/ShortsFilter;"

@Suppress("unused")
val hideShortsComponentsPatch = bytecodePatch(
    name = "Hide Shorts components",
    description = "Adds options to hide components related to Shorts."
) {
    dependsOn(
        engagementPanelHookPatch,
        hideShortsComponentsResourcePatch,
        layoutReloadObserverPatch,
        lithoFilterPatch,
        navigationBarHookPatch,
        resourceMappingPatch,
        sharedExtensionPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    hideShortsAppShortcutOption()
    hideShortsWidgetOption()

    execute {
        addLithoFilter(EXTENSION_FILTER)

        // region Hide sound button.

        if (!is_21_05_or_greater) {
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
                        invoke-static { v$sizeRegister }, $EXTENSION_FILTER->getSoundButtonSize(I)I
                        move-result v$sizeRegister
                    """
                )
            }
        }

        // endregion

        // region Hide the navigation bar.

        // Set the bottom bar container view.
        addBottomBarContainerHook(
            descriptor = "$EXTENSION_FILTER->setBottomBarContainer(Landroid/view/View;)V",
            highPriority = true
        )

        // Set the pivotBar view.
        SetPivotBarVisibilityFingerprint.let { result ->
            result.method.apply {
                val insertIndex = result.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(insertIndex - 1).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static {v$viewRegister}," +
                            "$EXTENSION_FILTER->setPivotBar(Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;)V",
                )
            }
        }

        // Hook to hide the pivotBar when the Shorts player is opened.
        ReelWatchFragmentInitPlaybackFingerprint.instructionMatches.last().getMethodCalled()
            .addInstruction(
                0,
                "invoke-static { p1 }, $EXTENSION_FILTER->hidePivotBar(Ljava/lang/String;)V",
            )

        // Hide the bottom bar container of the Shorts player.
        ShortsBottomBarContainerFingerprint.let {
            it.method.apply {
                val targetIndex = it.instructionMatches.last().index
                val heightRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                addInstructions(
                    targetIndex + 1,
                    """
                        invoke-static { v$heightRegister }, $EXTENSION_FILTER->getNavigationBarHeight(I)I
                        move-result v$heightRegister
                    """
                )
            }
        }

        // endregion


        // region Disable experimental Shorts flags.

        // Flags might be present in earlier targets, but they are not found in 19.47.53.
        // If these flags are forced on, the experimental layout is still not used, and
        // it appears the features requires additional server side data to fully use.

        // Experimental Shorts player uses Android native buttons and not Litho,
        // and the layout is provided by the server.
        //
        // Since the buttons are native components and not Litho, it should be possible to
        // fix the RYD Shorts loading delay by asynchronously loading RYD and updating
        // the button text after RYD has loaded.
        ShortsExperimentalPlayerFeatureFlagFingerprint.method.returnLate(false)

        // Experimental UI renderer must also be disabled since it requires the
        // experimental Shorts player. If this is enabled but Shorts player
        // is disabled then the app crashes when the Shorts player is opened.
        RenderNextUIFeatureFlagFingerprint.method.returnLate(false)

        // endregion

        DoubleTapToLikeLogicFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_FILTER->allowDoubleTapToLike(Z)Z
                        move-result v$register
                    """
                )
            }
        }
    }
}
