package app.morphe.patches.youtube.ad.general

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.fix.verticalscroll.verticalScrollPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.fix.backtoexitgesture.fixBackToExitGesturePatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.injectHideViewCall
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal var adAttributionId = -1L
    private set
internal var fullScreenEngagementAdContainer = -1L
    private set

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/components/AdsFilter;"

private val hideAdsResourcePatch = resourcePatch {
    dependsOn(
        lithoFilterPatch,
        settingsPatch,
        resourceMappingPatch,
    )

    execute {
        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("morphe_hide_creator_store_shelf"),
            SwitchPreference("morphe_hide_end_screen_store_banner"),
            SwitchPreference("morphe_hide_fullscreen_ads"),
            SwitchPreference("morphe_hide_general_ads"),
            SwitchPreference("morphe_hide_merchandise_banners"),
            SwitchPreference("morphe_hide_paid_promotion_label"),
            SwitchPreference("morphe_hide_self_sponsor_ads"),
            SwitchPreference("morphe_hide_shopping_links"),
            SwitchPreference("morphe_hide_view_products_banner"),
            SwitchPreference("morphe_hide_web_search_results"),
            SwitchPreference("morphe_hide_youtube_premium_promotions"),
        )

        addLithoFilter("Lapp/morphe/extension/youtube/patches/components/AdsFilter;")

        adAttributionId = getResourceId(ResourceType.ID, "ad_attribution")
        fullScreenEngagementAdContainer = getResourceId(ResourceType.ID, "fullscreen_engagement_ad_container")
    }
}

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Adds options to remove general ads.",
) {
    dependsOn(
        hideAdsResourcePatch,
        verticalScrollPatch,
        fixBackToExitGesturePatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.37.48",
        )
    )

    execute {
        // Hide end screen store banner

        FullScreenEngagementAdContainerFingerprint.method.apply {
            val addListIndex = indexOfAddListInstruction(this)
            val addListInstruction = getInstruction<FiveRegisterInstruction>(addListIndex)
            val listRegister = addListInstruction.registerC
            val objectRegister = addListInstruction.registerD

            replaceInstruction(
                addListIndex,
                "invoke-static { v$listRegister, v$objectRegister }, $EXTENSION_CLASS_DESCRIPTOR" +
                        "->hideEndScreenStoreBanner(Ljava/util/List;Ljava/lang/Object;)V"
            )
        }

        // Hide fullscreen ad

        LithoDialogBuilderFingerprint.let {
            it.method.apply {
                // Find the class name of the custom dialog
                val dialogClass = it.instructionMatches.first().instruction.getReference<MethodReference>()!!.definingClass

                // The dialog can be closed after dialog.show(),
                // and it is better to close the dialog after the layout of the dialog has changed
                val insertIndex = indexOfFirstInstructionReversedOrThrow {
                    opcode == Opcode.IPUT_OBJECT &&
                            getReference<FieldReference>()?.type == dialogClass
                }
                val insertRegister =
                    getInstruction<TwoRegisterInstruction>(insertIndex).registerA
                val freeRegister = findFreeRegister(insertIndex, insertRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        move-object/from16 v$freeRegister, p1
                        invoke-static { v$insertRegister, v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->closeFullscreenAd(Ljava/lang/Object;[B)V
                    """
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
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideGetPremiumView()Z
                    move-result v$tempRegister
                    if-eqz v$tempRegister, :allow
                    const/4 v$measuredWidthRegister, 0x0
                    const/4 v$measuredHeightRegister, 0x0
                    :allow
                    nop
                    # Layout width/height is then passed to a protected class method.
                """,
            )
        }

        // Hide ad views

        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
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
                            mutableClassDefBy(classDef)
                                .findMutableMethodOf(method)
                                .injectHideViewCall(
                                    insertIndex,
                                    viewRegister,
                                    EXTENSION_CLASS_DESCRIPTOR,
                                    "hideAdAttributionView",
                                )
                        }
                    }
                }
            }
        }
    }
}
