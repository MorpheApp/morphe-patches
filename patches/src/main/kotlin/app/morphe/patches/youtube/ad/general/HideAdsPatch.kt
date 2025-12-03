package app.morphe.patches.youtube.ad.general

import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.fix.verticalscroll.verticalScrollPatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.ad.getpremium.hideGetPremiumPatch
import app.morphe.patches.youtube.misc.fix.backtoexitgesture.fixBackToExitGesturePatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.injectHideViewCall
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31i
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

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
        addResourcesPatch,
    )

    execute {
        addResources("youtube", "ad.general.hideAdsResourcePatch")

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
        hideGetPremiumPatch,
        hideAdsResourcePatch,
        verticalScrollPatch,
        fixBackToExitGesturePatch,
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

    execute {
        // Hide end screen store banner

        fullScreenEngagementAdContainerFingerprint.method.apply {
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

        // Hide ad views

        classes.forEach { classDef ->
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
