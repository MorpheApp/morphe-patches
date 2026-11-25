/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5189
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SearchHiddenProfilesPatch;"
private const val PROFILE_MODEL_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SearchHiddenProfilesPatch$ProfileModel;"

@Suppress("unused")
val searchHiddenProfilesPatch = patch(
    name = "Search hidden profiles",
    description = "Adds an option to make the profile search find the posts of profiles that hide their posts or comments."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        ProfileModelToStringFingerprint.let {
            it.classDef.apply {
                addPatchInterface(PROFILE_MODEL_INTERFACE, "patch_getUsername", it.instructionMatches[1].fieldReference)
                addPatchInterface(PROFILE_MODEL_INTERFACE, "patch_isProfileFiltered", it.instructionMatches[3].fieldReference)
            }
        }

        // Open the search results of the posts of the profile instead of search scoped to the profile.
        ProfileSearchClickFingerprint.let {
            it.method.apply {
                val modelField = it.instructionMatches.first().fieldReference
                val openSearchMatch = it.instructionMatches.last()
                val openSearchIndex = openSearchMatch.index
                val freeRegister = findFreeRegister(openSearchIndex, openSearchMatch.instruction.registersUsed)
                if (freeRegister > 15) {
                    throw PatchException("No 4-bit free register to open the profile search in $this")
                }

                addInstructionsWithLabels(
                    openSearchIndex,
                    """
                        move-object/from16 v$freeRegister, p0
                        iget-object v$freeRegister, v$freeRegister, $modelField
                        invoke-static { v$freeRegister }, $EXTENSION_CLASS->openProfileSearch($PROFILE_MODEL_INTERFACE)Z
                        move-result v$freeRegister
                        if-nez v$freeRegister, :skip_profile_search
                    """,
                    ExternalLabel("skip_profile_search", getInstruction(openSearchIndex + 1))
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
