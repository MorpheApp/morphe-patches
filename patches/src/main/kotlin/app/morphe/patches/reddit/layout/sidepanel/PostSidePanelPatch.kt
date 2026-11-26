/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidepanel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_48_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/PostSidePanelPatch;"

@Suppress("unused")
val postSidePanelPatch = patch(
    name = "Post side panel",
    description = "Adds an option to open posts in a panel next to the feed on wide tablet screens."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        // Show the post screen as a dialog, which keeps the feed visible behind it.
        val dialogConstructor =
            (if (is_2026_48_0_or_greater) DialogPresentationFingerprint else DialogPresentationLegacyFingerprint)
                .instructionMatches.first().methodReference

        PostDetailPresentationFingerprint.let {
            val createPanelPresentation = ImmutableMethod(
                it.classDef.type,
                "patch_createPanelPresentation",
                listOf(),
                it.method.returnType,
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        new-instance v0, ${dialogConstructor.definingClass}
                        const/4 v1, 0x1 # Close when the backdrop is clicked.
                        const/4 v2, 0x0
                        const/16 v3, 0x1e # Default values for the other parameters.
                        invoke-direct { v0, v1, v2, v2, v3 }, $dialogConstructor
                        return-object v0
                    """
                )
            }
            it.classDef.methods.add(createPanelPresentation)

            // Some app versions have no free register in this method.
            it.method.cloneParameters().addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->usePanel(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :full_screen
                    invoke-static { }, $createPanelPresentation
                    move-result-object v0
                    return-object v0
                    :full_screen
                    nop
                """
            )
        }

        // Lay out the dialog as a panel.
        ScreenCreateViewFingerprint.let {
            val screenRegister = it.instructionMatches[4].instruction.registersUsed.first()
            val returnMatch = it.instructionMatches.last()
            val viewRegister = returnMatch.instruction.registersUsed.first()
            if (screenRegister > 15 || viewRegister > 15) {
                throw PatchException("Registers out of range: v$screenRegister v$viewRegister")
            }

            it.method.addInstructionsAtControlFlowLabel(
                returnMatch.index,
                "invoke-static { v$screenRegister, v$viewRegister }, $EXTENSION_CLASS->" +
                        "onScreenViewCreated(Ljava/lang/Object;Landroid/view/View;)V"
            )
        }

        // The card transition expands the post to full screen, so open the panel without it.
        val pushScreen = OpenPostFingerprint.instructionMatches.last().methodReference

        OpenPostWithTransitionFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->usePanel(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :card_transition
                move-object/from16 v0, p1
                move-object/from16 v1, p3
                const/4 v2, 0x0
                invoke-static { v0, v1, v2 }, $pushScreen
                return-void
                :card_transition
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
