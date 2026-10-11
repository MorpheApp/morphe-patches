/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.post

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.flag.featureFlagHookPatch
import app.morphe.patches.reddit.misc.flag.hookFeatureFlag
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_39_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.util.logging.Logger

private const val EXTENSION_VIEW_COUNT_CLASS =
    "Lapp/morphe/extension/reddit/patches/ShowViewCountPatch;"

private const val EXTENSION_FLIP_ACTION_BAR_CLASS =
    "Lapp/morphe/extension/reddit/patches/FlipPostActionBarPatch;"

private const val EXTENSION_ROW_ARRANGEMENT_CLASS =
    $$"Lapp/morphe/extension/reddit/patches/FlipPostActionBarPatch$RowArrangement;"

@Suppress("unused")
val customizePostLayoutPatch = bytecodePatch(
    name = "Customize post layout",
    description = "Adds options to show the view count of posts, to move the vote and comment buttons " +
            "of posts to the right side, and to swap the vote and comment buttons. " +
            "Moving and swapping the buttons works with Reddit 2026.39.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        featureFlagHookPatch,
        versionCheckPatch
    )

    execute {
        hookFeatureFlag("$EXTENSION_VIEW_COUNT_CLASS->showViewCount")

        setExtensionIsPatchIncluded(EXTENSION_VIEW_COUNT_CLASS)

        if (is_2026_39_0_or_greater) {
            flipPostActionBar()
        } else {
            Logger.getLogger(this::class.java.name).warning(
                "'Customize post layout' action bar options require Reddit 2026.39.0+"
            )
        }
    }
}

private fun BytecodePatchContext.flipPostActionBar() {
    // Implement the Compose horizontal arrangement interface with the extension class.
    ArrangementHorizontalArrangeFingerprint.let {
        mutableClassDefBy(EXTENSION_ROW_ARRANGEMENT_CLASS).apply {
            interfaces.add(it.classDef.type)
            methods.add(
                ImmutableMethod(
                    type,
                    it.method.name,
                    it.method.parameters,
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(6),
                ).toMutable().apply {
                    // Parameters: density, totalSize, sizes, layoutDirection, outPositions.
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0, p2, p3, p4, p5 }, $EXTENSION_ROW_ARRANGEMENT_CLASS->arrange(I[ILandroidx/compose/ui/unit/LayoutDirection;[I)V
                            return-void
                        """
                    )
                }
            )
        }
    }

    PostActionBarRowFingerprint.let {
        it.method.apply {
            val appearanceIndex = it.instructionMatches[0].index
            val voteIndex = it.instructionMatches[1].index
            val buttonsRowIndex = it.instructionMatches[3].index
            val commentRowIndex = it.instructionMatches[6].index
            val commentIndex = it.instructionMatches.last().index

            val appearanceMethod = getInstruction(appearanceIndex).getReference<MethodReference>()
            val voteMethod = getInstruction(voteIndex).getReference<MethodReference>()
            // invoke-static/range { comment, ..., composer, flags }
            val commentComposerRegister = getInstruction(commentIndex).registersUsed[6]

            // From the last change to the first, so the indexes stay valid.

            // Show the vote buttons in the comment row, before the comment button.
            val registerProvider = getFreeRegisterProvider(commentIndex, 5)
            val stateRegister = registerProvider.getFreeRegister4Bit()
            val appearanceRegister = registerProvider.getFreeRegister4Bit()
            val modifierRegister = registerProvider.getFreeRegister4Bit()
            val composerRegister = registerProvider.getFreeRegister4Bit()
            val flagsRegister = registerProvider.getFreeRegister4Bit()
            addInstructionsWithLabels(
                commentIndex,
                """
                    invoke-static {}, $EXTENSION_FLIP_ACTION_BAR_CLASS->moveVoteButtons()Z
                    move-result v$stateRegister
                    if-eqz v$stateRegister, :comment
                    move-object/from16 v$stateRegister, p0
                    move-object/from16 v$appearanceRegister, p1
                    invoke-static { v$appearanceRegister }, $appearanceMethod
                    move-result-object v$appearanceRegister
                    const/4 v$modifierRegister, 0x0
                    move-object/from16 v$composerRegister, v$commentComposerRegister
                    const/4 v$flagsRegister, 0x0
                    invoke-static { v$stateRegister, v$appearanceRegister, v$modifierRegister, v$composerRegister, v$flagsRegister }, $voteMethod
                    :comment
                    nop
                """
            )

            // Place the vote and comment buttons in the comment row, at its end when flipped,
            // and the other buttons before the comment row when flipped.
            fun changeArrangement(index: Int, extensionMethod: String) {
                val instruction = getInstruction(index)
                val arrangementRegister = instruction.registersUsed[0]
                val arrangementType = instruction.getReference<MethodReference>()!!.parameterTypes[0]

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$arrangementRegister }, $EXTENSION_FLIP_ACTION_BAR_CLASS->$extensionMethod(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$arrangementRegister
                        check-cast v$arrangementRegister, $arrangementType
                    """
                )
            }

            changeArrangement(commentRowIndex, "getCommentRowArrangement")
            changeArrangement(buttonsRowIndex, "getButtonsRowArrangement")

            // Don't show the vote buttons before the buttons row.
            val skipRegister = findFreeRegister(voteIndex)
            addInstructionsWithLabels(
                voteIndex,
                """
                    invoke-static {}, $EXTENSION_FLIP_ACTION_BAR_CLASS->moveVoteButtons()Z
                    move-result v$skipRegister
                    if-nez v$skipRegister, :skip
                """,
                ExternalLabel("skip", getInstruction(voteIndex + 1))
            )
        }
    }

    setExtensionIsPatchIncluded(EXTENSION_FLIP_ACTION_BAR_CLASS)
}
