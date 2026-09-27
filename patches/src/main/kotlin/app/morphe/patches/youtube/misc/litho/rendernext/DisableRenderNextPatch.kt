/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.litho.rendernext

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_20_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_47_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/**
 * RenderNext (ElementsView) is a Google UI library alternative to Litho, enabled by some A/B tests.
 * Items rendered with it are not seen by the Litho filters and hooks, so it's always disabled.
 */
val disableRenderNextPatch = bytecodePatch(
    description = "Disables RenderNext, so the app is always rendered with Litho.",
) {
    dependsOn(versionCheckPatch)

    execute {
        // Do not provide ElementsServices to the section list, required to convert Litho components.
        // The flag is inlined in 21.30+, so it's overridden only where the section list uses it.
        (if (is_21_30_or_greater) {
            RenderNextPresentContextFeatureFlagFingerprint
        } else {
            RenderNextFeatureFlagFingerprint
        }).let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                false
            )
        }

        // Do not present elements with RenderNext, even if the server enables it.
        // An element is RenderNext if the enablement check of its proto
        // or the RenderNext field of its config is true.
        val enablementCheckMethod = RenderNextEnablementCheckFingerprint.method
        enablementCheckMethod.returnEarly(false)

        val configRenderNextField = getRenderNextConfigFieldFingerprint(enablementCheckMethod)
            .instructionMatches.first().getFieldAccessed()

        // Every read of the config field decides if an element is RenderNext.
        getRenderNextConfigFieldReadFingerprint(configRenderNextField).matchAll().forEach {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "const/4 v$register, 0x0"
                )
            }
        }

        if (is_20_29_or_greater) {
            // Do not convert Litho components to RenderNext.
            RenderNextTemplateCheckFingerprint.method.returnEarly(false)
        }

        if (is_20_47_or_greater) {
            // Safeguard: always present elements with Litho, even if they are RenderNext elements.
            HybridElementPresenterFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstruction(
                        index + 1,
                        "const/4 v$register, 0x0"
                    )
                }
            }
        }
    }
}
