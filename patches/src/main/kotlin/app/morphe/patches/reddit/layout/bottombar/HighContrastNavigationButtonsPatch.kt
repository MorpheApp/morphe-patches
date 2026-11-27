/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5460
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
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
        val matches = BottomNavButtonColorFingerprint.matchAll()
        if (matches.isEmpty()) {
            throw PatchException("BottomNavButtonColorFingerprint did not match any methods.")
        }

        val labelClass = BottomNavButtonLabelFingerprint.instructionMatches[1].methodReference.definingClass
        val labelColorMatch = Fingerprint(
            definingClass = labelClass,
            name = "invoke",
            returnType = "Ljava/lang/Object;",
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            filters = unselectedButtonColorFilters
        ).match()

        (matches + labelColorMatch).forEach { match ->
            val colorMatch = match.instructionMatches.last()
            val register = (colorMatch.instruction as OneRegisterInstruction).registerA

            match.method.addInstructions(
                colorMatch.index + 1,
                """
                    invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getUnselectedButtonColor(J)J
                    move-result-wide v$register
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
