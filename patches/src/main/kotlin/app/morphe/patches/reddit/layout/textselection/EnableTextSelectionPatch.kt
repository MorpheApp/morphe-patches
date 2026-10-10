package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/EnableTextSelectionPatch;"

// Arbitrary Compose group key of the lambda passed to SelectionContainer.
private const val SELECTION_CONTENT_LAMBDA_KEY = 0x4D4F5250

private fun MutableMethod.smaliReference() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

@Suppress("unused")
val enableTextSelectionPatch = bytecodePatch(
    name = "Enable text selection",
    description = "Adds an option to make post and comment text selectable and copyable. " +
        "Disables collapsing comments."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // region Wrap the rich text renderer in a SelectionContainer.

        val richTextMethod = ComposeRichTextFingerprint.method
        val paramTypes = richTextMethod.parameterTypes.map { it.toString() }

        // The trailing int parameters are the Compose $changed/$default flags and the
        // parameter before them is the Composer.
        val composerIndex = paramTypes.indexOfLast { it != "I" }
        if (composerIndex < 0 || composerIndex == paramTypes.size - 1) {
            throw PatchException("Unexpected Compose rich text signature: $paramTypes")
        }
        val composerRegister = "p$composerIndex"

        val composableLambdaMethod = ComposeComposableLambdaFingerprint.method
        val blockType = composableLambdaMethod.parameterTypes[1]
        val selectionContainerMethod = ComposeSelectionContainerFingerprint.method

        // Parameter registers are above v15, which is why range invokes and
        // move-object/from16 are used.
        val packArguments = buildString {
            paramTypes.forEachIndexed { index, type ->
                appendLine("const/16 v1, $index")
                when (type) {
                    "Z" -> appendLine(
                        "invoke-static/range { p$index .. p$index }, " +
                            "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"
                    )
                    "I" -> appendLine(
                        "invoke-static/range { p$index .. p$index }, " +
                            "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"
                    )
                    "J", "D", "F", "B", "S", "C" ->
                        throw PatchException("Unsupported primitive parameter type: $type")
                    else -> appendLine("aput-object p$index, v0, v1")
                }
                if (type == "Z" || type == "I") {
                    appendLine("move-result-object v2")
                    appendLine("aput-object v2, v0, v1")
                }
            }
        }

        richTextMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->shouldWrapRichText()Z
                move-result v0
                if-eqz v0, :morphe_original

                const/16 v0, ${paramTypes.size}
                new-array v0, v0, [Ljava/lang/Object;
                $packArguments
                const-class v1, ${richTextMethod.definingClass}
                const-string v2, "${richTextMethod.name}"
                invoke-static { v1, v2, v0 }, $EXTENSION_CLASS->createRichTextContent(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $blockType
                const v2, $SELECTION_CONTENT_LAMBDA_KEY
                move-object/from16 v3, $composerRegister
                invoke-static { v2, v1, v3 }, ${composableLambdaMethod.smaliReference()}
                move-result-object v1

                const/16 v0, 48
                const/4 v2, 0x1
                move-object/from16 v3, $composerRegister
                const/4 v4, 0x0
                invoke-static { v0, v2, v3, v4, v1 }, ${selectionContainerMethod.smaliReference()}
                return-void

                :morphe_original
                nop
            """
        )

        // endregion

        // region Disable collapsing comments, expanding a collapsed comment keeps working.

        CommentClickEventHandlerFingerprint.method.apply {
            val instructions = implementation!!.instructions

            // Find the branch that collapses an expanded comment:
            //   invoke-virtual { comment }, Comment->getCollapsed()Z
            //   move-result vX
            //   if-eqz vX, :collapse
            var branchIndex = -1
            var collapsedRegister = -1
            for (i in 0 until instructions.size - 3) {
                val call = instructions[i]
                if (call.opcode != Opcode.INVOKE_VIRTUAL) continue
                val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
                if (reference?.name != "getCollapsed") continue

                val result = instructions[i + 1]
                if (result.opcode != Opcode.MOVE_RESULT) continue
                val register = (result as OneRegisterInstruction).registerA

                for (j in i + 2..i + 3) {
                    val branch = instructions[j]
                    if (branch.opcode == Opcode.IF_EQZ &&
                        (branch as OneRegisterInstruction).registerA == register
                    ) {
                        branchIndex = j
                        collapsedRegister = register
                        break
                    }
                }
                if (branchIndex >= 0) break
            }
            if (branchIndex < 0) throw PatchException("Could not find comment collapsed check")

            val unitInstance = instructions
                .filterIsInstance<ReferenceInstruction>()
                .firstOrNull {
                    it.opcode == Opcode.SGET_OBJECT &&
                        (it.reference as? FieldReference)?.definingClass == "Lkotlin/Unit;"
                }?.reference ?: throw PatchException("Could not find Unit instance field")

            // The register is zero when the comment is expanded, the next instruction then
            // collapses it. Return early instead, but only if the setting is on.
            addInstructionsWithLabels(
                branchIndex,
                """
                    if-nez v$collapsedRegister, :morphe_comment_collapsed
                    invoke-static { }, $EXTENSION_CLASS->shouldBlockCommentCollapse()Z
                    move-result v$collapsedRegister
                    if-eqz v$collapsedRegister, :morphe_comment_collapse_allowed
                    sget-object v$collapsedRegister, $unitInstance
                    return-object v$collapsedRegister
                    :morphe_comment_collapse_allowed
                    const/16 v$collapsedRegister, 0x0
                    :morphe_comment_collapsed
                    nop
                """
            )
        }

        // endregion

        // region A slight drag must not be treated as a tap that clears the selection.

        SelectionTapDetectionFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.first().index,
                "invoke-static { }, $EXTENSION_CLASS->beginSelectionTapDetection()V"
            )
        }

        PointersUpSlopDetectionFingerprint.let {
            it.method.apply {
                val callIndex = it.instructionMatches.first().index
                val moveResult = getInstruction<OneRegisterInstruction>(callIndex + 1)
                if (moveResult.opcode != Opcode.MOVE_RESULT) {
                    throw PatchException("Unexpected instruction after touch slop call")
                }
                val slopRegister = moveResult.registerA

                addInstructions(
                    callIndex + 2,
                    """
                        invoke-static/range { v$slopRegister .. v$slopRegister }, $EXTENSION_CLASS->adjustSelectionTapSlop(F)F
                        move-result v$slopRegister
                    """
                )
            }
        }

        // endregion

        // region Keep the selection while touching and scrolling, a tap clears it.

        val releaseMethod = SelectionReleaseFingerprint.method
        releaseMethod.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onSelectionRelease(Ljava/lang/Object;)V"
        )

        // The extension clears selections by calling the release method.
        SelectionChangedFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "${releaseMethod.name}"
                invoke-static { p0, p1, v0 }, $EXTENSION_CLASS->onSelectionChanged(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V
            """
        )

        ComposeDispatchTouchEventFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onComposeTouchEvent(Landroid/view/MotionEvent;)V"
        )

        FocusClearFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->shouldBlockFocusClear()Z
                move-result v0
                if-eqz v0, :morphe_clear_focus
                return-void
                :morphe_clear_focus
                nop
            """
        )

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
