/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5296
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.answers

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideAnswersPreviewPatch;"

@Suppress("unused")
val hideAnswersPreviewPatch = patch(
    name = "Hide Answers preview",
    description = "Adds an option to hide the Reddit Answers preview at the top of search results."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        listOf(
            SearchAnswersPreviewSectionToStringFingerprint,
            SearchAnswersPreviewStreamingSectionToStringFingerprint
        ).forEach { sectionFingerprint ->
            val sectionInterface = classDefBy(sectionFingerprint.originalClassDef.interfaces.single())
            val contentMethod = sectionInterface.methods.single { it.returnType == "V" }

            Fingerprint(
                classFingerprint = sectionFingerprint,
                name = contentMethod.name,
                returnType = "V",
                parameters = contentMethod.parameterTypes.map { it.toString() }
            ).method.returnEarlyIf("invoke-static { }, $EXTENSION_CLASS->hideAnswersPreview()Z")
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun MutableMethod.returnEarlyIf(condition: String) {
    val register = findFreeRegister(0)
    addInstructionsWithLabels(
        0,
        """
            $condition
            move-result v$register
            if-eqz v$register, :show
            return-void
            :show
            nop
        """
    )
}
