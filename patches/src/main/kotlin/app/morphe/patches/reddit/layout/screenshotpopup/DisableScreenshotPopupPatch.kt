/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.screenshotpopup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_33_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_43_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DisableScreenshotPopupPatch;"

@Suppress("unused")
val disableScreenshotPopupPatch = bytecodePatch(
    name = "Disable screenshot popup",
    description = "Adds an option to disable the popup that appears when taking a screenshot."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        fun MutableMethod.disableScreenshotPopup(booleanIndex: Int) {
            val booleanRegister = getInstruction<OneRegisterInstruction>(booleanIndex).registerA

            addInstructions(
                booleanIndex + 1,
                """
                    invoke-static { v$booleanRegister }, $EXTENSION_CLASS->disableScreenshotPopup(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                    move-result-object v$booleanRegister
                """
            )
        }

        if (is_2026_33_0_or_greater) {
            ScreenshotShareSheetFingerprint.let {
                it.method.disableScreenshotPopup(it.instructionMatches.first().index)
            }
        }

        // Banner was removed in 2026.43.0.
        if (!is_2026_43_0_or_greater) {
            listOf(
                RedditScreenshotTriggerSharingListenerFingerprint,
                ScreenshotTakenBannerFingerprint
            ).forEach {
                it.method.disableScreenshotPopup(it.instructionMatches[1].index)
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
