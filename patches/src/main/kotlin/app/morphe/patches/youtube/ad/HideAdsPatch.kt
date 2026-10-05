/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.ad.hideFullscreenAdsPatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.hide.shelves.hideHorizontalShelvesPatch
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.engagement.addEngagementPanelIdHook
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.BuildClientContextBodyConstructorFingerprint
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/components/AdsFilter;"

private val hideAdsResourcePatch = resourcePatch {
    dependsOn(
        lithoFilterPatch,
        settingsPatch,
        clientContextHookPatch,
        engagementPanelHookPatch,
        hideHorizontalShelvesPatch,
        elementProtoParserHookPatch
    )

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_hide_creator_store_shelf"),
            SwitchPreference("morphe_hide_end_screen_store_banner"),
            SwitchPreference("morphe_hide_general_ads"),
            SwitchPreference("morphe_hide_merchandise_banners"),
            SwitchPreference("morphe_hide_paid_promotion_labels"),
            SwitchPreference("morphe_hide_player_popup_ads"),
            SwitchPreference("morphe_hide_self_sponsor_ads"),
            SwitchPreference("morphe_hide_shopping_links"),
            SwitchPreference("morphe_hide_video_ads"),
            SwitchPreference("morphe_hide_youtube_premium_promotions"),
        )

        addLithoFilter(EXTENSION_CLASS)
        addEngagementPanelIdHook("$EXTENSION_CLASS->hidePlayerPopupAds(Ljava/lang/String;)Z")
    }
}

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to hide general ads, Premium promotions and video ads."
) {
    dependsOn(
        hideAdsResourcePatch,
        elementProtoParserHookPatch,
        versionCheckPatch,
        sharedExtensionPatch,
        elementProtoParserHookPatch,
        hideFullscreenAdsPatch(PreferenceScreen.ADS)
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Hide video ads

        setOf(
            LoadVideoAdsFingerprint,
            PlayerBytesAdLayoutFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->hideVideoAds()Z
                    move-result v0
                    if-eqz v0, :show_video_ads
                    return-void
                    :show_video_ads
                    nop
                """
            )
        }

        BuildClientContextBodyConstructorFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches[1]
                val index = match.index
                val register = match.instruction.registersUsed[0]

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->hideAds(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        // Hide YouTube Premium promotions

        hookElement("$EXTENSION_CLASS->hideStatementBanner")

        // Hide end screen store banner

        FullScreenEngagementAdContainerFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches[3].index
                val insertInstruction = getInstruction<FiveRegisterInstruction>(insertIndex)
                val listRegister = insertInstruction.registerC
                val objectRegister = insertInstruction.registerD

                replaceInstruction(
                    insertIndex,
                    "invoke-static { v$listRegister, v$objectRegister }, $EXTENSION_CLASS->" +
                            "hideEndScreenStoreBanner(Ljava/util/List;Ljava/lang/Object;)V"
                )
            }
        }

        // Hide get premium

        GetPremiumViewFingerprint.method.apply {
            val startIndex = GetPremiumViewFingerprint.instructionMatches.first().index
            val measuredWidthRegister = getInstruction<TwoRegisterInstruction>(startIndex).registerA
            val measuredHeightInstruction = getInstruction<TwoRegisterInstruction>(startIndex + 1)

            val measuredHeightRegister = measuredHeightInstruction.registerA
            val tempRegister = measuredHeightInstruction.registerB

            addInstructionsWithLabels(
                startIndex + 2,
                """
                    # Override the internal measurement of the layout with zero values.
                    invoke-static {}, $EXTENSION_CLASS->hideGetPremiumView()Z
                    move-result v$tempRegister
                    if-eqz v$tempRegister, :allow
                    const/4 v$measuredWidthRegister, 0x0
                    const/4 v$measuredHeightRegister, 0x0
                    :allow
                    nop
                    # Layout width/height is then passed to a protected class method.
                """
            )
        }

        // Hide player overlay view. This can be hidden with a regular litho filter
        // but an empty space remains.

        PlayerOverlayTimelyShelfFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsWithLabels(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->allowAds(Z)Z
                        move-result v$register
                        if-nez v$register, :show
                        return-void
                        :show
                        nop
                    """
                )
            }
        }

        // Hide ad views

        resourceLiteral(
            ResourceType.ID, "ad_attribution"
        ).matchAllMethodIndicesForEach { index ->
            val insertIndex = index + 1

            // Call to get the view with the id adAttribution
            val invokeInstruction = getInstruction(insertIndex)
            if (invokeInstruction.opcode != Opcode.INVOKE_VIRTUAL) {
                return@matchAllMethodIndicesForEach
            }

            // Hide the view
            val viewRegister = (invokeInstruction as FiveRegisterInstruction).registerC
            injectHideViewCall(
                insertIndex,
                viewRegister,
                EXTENSION_CLASS,
                "hideAdAttributionView",
            )
        }

        // Hide paid promotion label in miniplayer

        MiniplayerPaidPromotionLabelFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val targetInstruction = getInstruction<OneRegisterInstruction>(insertIndex)
                val viewRegister = targetInstruction.registerA

                injectHideViewCall(
                    insertIndex + 1,
                    viewRegister,
                    EXTENSION_CLASS,
                    "hideMiniplayerPaidPromotionLabelView"
                )
            }
        }

        setOf(
            Endpoint.BROWSE,
            Endpoint.SEARCH,
            Endpoint.NEXT,
        ).forEach { endpoint ->
            addOSNameHook(
                endpoint,
                "$EXTENSION_CLASS->hideAds(Ljava/lang/String;)Ljava/lang/String;"
            )
        }

        setOf(
            Endpoint.GET_WATCH,
            Endpoint.PLAYER,
            Endpoint.REEL,
        ).forEach { endpoint ->
            addOSNameHook(
                endpoint,
                "$EXTENSION_CLASS->hideVideoAds(Ljava/lang/String;)Ljava/lang/String;"
            )
        }

        addOSNameHook(
            Endpoint.GUIDE,
            "$EXTENSION_CLASS->overrideGuideOSName(Ljava/lang/String;)Ljava/lang/String;"
        )
    }
}

/**
 * Inject a call to a method that hides a view.
 *
 * @param moveIndex The index of MOVE_RESULT_OBJECT.
 * @param classDescriptor The descriptor of the class that contains the method.
 * @param targetMethod The name of the method to call.
 */
internal fun MutableMethod.injectHideViewCall(
    moveIndex: Int,
    classDescriptor: String,
    targetMethod: String,
) = injectHideViewCall(
    moveIndex + 1,
    getInstruction<OneRegisterInstruction>(moveIndex).registerA,
    classDescriptor,
    targetMethod
)

/**
 * Inject a call to a method that hides a view.
 *
 * @param insertIndex The index to insert the call at.
 * @param viewRegister The register of the view to hide.
 * @param classDescriptor The descriptor of the class that contains the method.
 * @param targetMethod The name of the method to call.
 */
internal fun MutableMethod.injectHideViewCall(
    insertIndex: Int,
    viewRegister: Int,
    classDescriptor: String,
    targetMethod: String,
) = addInstruction(
    insertIndex,
    "invoke-static { v$viewRegister }, $classDescriptor->$targetMethod(Landroid/view/View;)V",
)
