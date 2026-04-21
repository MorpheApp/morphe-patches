/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.ad.general

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.ad.hideFullscreenAdsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.hide.shelves.hideHorizontalShelvesPatch
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addOSNameHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.engagement.addEngagementPanelIdHook
import app.morphe.patches.youtube.misc.engagement.engagementPanelHookPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.proto.hookElement
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findMutableMethodOf
import app.morphe.util.injectHideViewCall
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/components/AdsFilter;"

private val hideAdsResourcePatch = resourcePatch {
    dependsOn(
        lithoFilterPatch,
        settingsPatch,
        clientContextHookPatch,
        engagementPanelHookPatch,
        hideHorizontalShelvesPatch,
    )

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_hide_end_screen_store_banner"),
            SwitchPreference("morphe_hide_general_ads"),
            SwitchPreference("morphe_hide_merchandise_banners"),
            SwitchPreference("morphe_hide_paid_promotion_label"),
            SwitchPreference("morphe_hide_player_popup_ads"),
            SwitchPreference("morphe_hide_self_sponsor_ads"),
            SwitchPreference("morphe_hide_shopping_links"),
            SwitchPreference("morphe_hide_view_products_banner"),
            SwitchPreference("morphe_hide_youtube_premium_promotions"),
        )

        addLithoFilter(EXTENSION_CLASS)
        addEngagementPanelIdHook("$EXTENSION_CLASS->hidePlayerPopupAds(Ljava/lang/String;)Z")
    }
}

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to remove general ads.",
) {
    dependsOn(
        hideAdsResourcePatch,
        elementProtoParserHookPatch,
        resourceMappingPatch,
        versionCheckPatch,
        hideFullscreenAdsPatch(PreferenceScreen.ADS)
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // Hide YouTube Premium promotions

        hookElement("$EXTENSION_CLASS->hideStatementBanner([B)[B")

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
        PlayerOverlayTimelyShelfFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS->hideAds()Z
                move-result v0
                if-eqz v0, :show
                return-void
                :show
                nop
            """
        )

        // Hide ad views.

        var adAttributionId = getResourceId(ResourceType.ID, "ad_attribution")

        classDefForEach { classDef ->
            val mutableClassDef by lazy {
                mutableClassDefBy(classDef)
            }
            classDef.methods.forEach { method ->
                val mutableMethod by lazy {
                    mutableClassDef.findMutableMethodOf(method)
                }

                with(method.implementation) {
                    this?.instructions?.forEachIndexed { index, instruction ->
                        if (instruction.opcode != Opcode.CONST) {
                            return@forEachIndexed
                        }
                        // Instruction to store the id adAttribution into a register
                        if ((instruction as Instruction31i).wideLiteral != adAttributionId) {
                            return@forEachIndexed
                        }

                        val insertIndex = index + 1

                        // Call to get the view with the id adAttribution
                        with(instructions.elementAt(insertIndex)) {
                            if (opcode != Opcode.INVOKE_VIRTUAL) {
                                return@forEachIndexed
                            }

                            // Hide the view
                            val viewRegister = (this as Instruction35c).registerC
                            mutableMethod.injectHideViewCall(
                                insertIndex,
                                viewRegister,
                                EXTENSION_CLASS,
                                "hideAdAttributionView",
                            )
                        }
                    }
                }
            }
        }

        setOf(
            Endpoint.BROWSE,
            Endpoint.SEARCH,
            Endpoint.NEXT,
        ).forEach { endpoint ->
            addOSNameHook(
                endpoint,
                "$EXTENSION_CLASS->hideAds(Ljava/lang/String;)Ljava/lang/String;",
            )
        }
    }
}
