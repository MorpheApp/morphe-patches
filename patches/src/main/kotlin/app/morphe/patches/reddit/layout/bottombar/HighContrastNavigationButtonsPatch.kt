/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5460
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HighContrastNavigationButtonsPatch;"

@Suppress("unused")
val highContrastNavigationButtonsPatch = patch(
    name = "High-contrast navigation buttons",
    description = "Adds an option to show the unselected buttons of the navigation bar in black or white instead of grey."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        /**
         * Replaces the color moved by the move-result-wide instruction at [moveResultIndex].
         */
        fun MutableMethod.hookUnselectedColor(moveResultIndex: Int) {
            val register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getUnselectedButtonColor(J)J
                    move-result-wide v$register
                """
            )
        }

        val labelClasses = BottomNavButtonColorFingerprint.matchAll().map { match ->
            val labelClass = match.instructionMatches[5].methodReference.definingClass
            match.method.hookUnselectedColor(match.instructionMatches[4].index)
            labelClass
        }

        // A label lambda class can be shared by both buttons, with the color chosen in a case of each.
        labelClasses.distinct().forEach { labelClass ->
            BottomNavButtonLabelFingerprint(labelClass).let {
                val unselectedColorMethod = it.instructionMatches[2].methodReference

                it.method.apply {
                    findInstructionIndicesReversedOrThrow(
                        methodCall(reference = unselectedColorMethod)
                    ).forEach { index ->
                        hookUnselectedColor(index + 1)
                    }
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
