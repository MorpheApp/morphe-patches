/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3665
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.copytext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CopyTextPatch;"

private const val EXTENSION_SELECTION_CONTENT_CLASS =
    $$"Lapp/morphe/extension/reddit/patches/CopyTextPatch$SelectionContent;"

private const val EXTENSION_SELECTION_MANAGER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/CopyTextPatch$SelectionManagerInterface;"

// Arbitrary Compose group key of the lambda passed to SelectionContainer.
private const val SELECTION_CONTENT_LAMBDA_KEY = 0x4D4F5250

// Parameter of the post title composable that makes the title clickable.
private const val POST_TITLE_CLICKABLE_PARAMETER_INDEX = 4

// Registers v0 to v4 are used by the SelectionContainer smali.
private const val SELECTION_CONTAINER_SMALI_REGISTERS = 5

val copyTextPatch = patch(
    name = "Copy text",
    description = "Adds an option to make post and comment text selectable and copyable. " +
        "This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Copy text' does not work with Reddit 2026.23.0 and earlier"
            )
        }

        // region Wrap the rich text renderer and the post title in a SelectionContainer.

        // rememberComposableLambda(key, block, composer)
        val composableLambdaMethod = ComposeComposableLambdaFingerprint.method
        val blockType = composableLambdaMethod.parameterTypes[1]
        val composerType = composableLambdaMethod.parameterTypes[2].toString()
        val selectionContainerMethod = ComposeSelectionContainerFingerprint.method

        /**
         * Smali that invokes a composable inside a SelectionContainer and returns. The arguments
         * are the parameters of the method the smali is added to, which has the parameters of the
         * composable. They are packed into an array, which the content lambda created by the
         * extension passes to the method added by `addInvokeComposableMethod`.
         */
        fun selectionContainerSmali(paramTypes: List<String>, createContentMethod: String): String {
            val composerRegister = "p${paramTypes.indexOf(composerType)}"

            // Parameter registers can be above v15, which is why range invokes and
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

            return """
                const/16 v0, ${paramTypes.size}
                new-array v0, v0, [Ljava/lang/Object;
                $packArguments
                invoke-static { v0 }, $EXTENSION_CLASS->$createContentMethod([Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $blockType
                const v2, $SELECTION_CONTENT_LAMBDA_KEY
                move-object/from16 v3, $composerRegister
                invoke-static { v2, v1, v3 }, $composableLambdaMethod
                move-result-object v1

                const/16 v0, 48
                const/4 v2, 0x1
                move-object/from16 v3, $composerRegister
                const/4 v4, 0x0
                invoke-static { v0, v2, v3, v4, v1 }, $selectionContainerMethod
                return-void
            """
        }

        /**
         * Adds a method that invokes [composable] with the arguments packed by `selectionContainerSmali`.
         * The composer argument is replaced with the composer of the SelectionContainer content.
         * The extension method of [invokerFingerprint] is changed to call the added method.
         */
        fun addInvokeComposableMethod(
            composable: MutableMethod,
            methodName: String,
            invokerFingerprint: Fingerprint
        ) {
            val paramTypes = composable.parameterTypes.map { it.toString() }
            val composerIndex = paramTypes.indexOf(composerType)

            // The registers v0 to v(n-1) hold the arguments, v(n) is the array index.
            val indexRegister = paramTypes.size
            val unpackArguments = buildString {
                paramTypes.forEachIndexed { index, type ->
                    if (index == composerIndex) {
                        appendLine("move-object/from16 v$index, p1")
                        return@forEachIndexed
                    }

                    appendLine("const/16 v$indexRegister, $index")
                    appendLine("aget-object v$index, p0, v$indexRegister")
                    when (type) {
                        "Z" -> appendLine(
                            """
                                check-cast v$index, Ljava/lang/Boolean;
                                invoke-virtual/range { v$index .. v$index }, Ljava/lang/Boolean;->booleanValue()Z
                                move-result v$index
                            """
                        )
                        "I" -> appendLine(
                            """
                                check-cast v$index, Ljava/lang/Integer;
                                invoke-virtual/range { v$index .. v$index }, Ljava/lang/Integer;->intValue()I
                                move-result v$index
                            """
                        )
                        else -> appendLine("check-cast v$index, $type")
                    }
                }
            }

            val invokeMethod = ImmutableMethod(
                composable.definingClass,
                methodName,
                listOf(
                    ImmutableMethodParameter("[Ljava/lang/Object;", null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null)
                ),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(paramTypes.size + 3)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        $unpackArguments
                        invoke-static/range { v0 .. v${paramTypes.size - 1} }, $composable
                        return-void
                    """
                )
            }
            mutableClassDefBy(composable.definingClass).methods.add(invokeMethod)

            invokerFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p0, p1 }, $invokeMethod
                    return-void
                """
            )
        }

        // Make the SelectionContainer content a Compose lambda.
        mutableClassDefBy(EXTENSION_SELECTION_CONTENT_CLASS).apply {
            addPatchInterface(
                "Lkotlin/jvm/functions/Function2;",
                "invoke",
                """
                    invoke-virtual { p0, p1 }, $type->render(Ljava/lang/Object;)V
                    const/4 v0, 0x0
                    return-object v0
                """
            )
        }

        // Rich text renderer, used for post and comment bodies.
        ComposeRichTextFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->shouldWrapRichText()Z
                    move-result v0
                    if-eqz v0, :morphe_original
                    ${selectionContainerSmali(parameterTypes.map { it.toString() }, "createRichTextContent")}
                    :morphe_original
                    nop
                """
            )

            addInvokeComposableMethod(this, "patch_invokeRichText", InvokeRichTextFingerprint)
        }

        // Post detail screen title. The title composable is also used for link previews,
        // so the post detail screen title section invokes it inside a SelectionContainer.
        val postTitleMethod = PostTitleFingerprint.method
        val postTitleParamTypes = postTitleMethod.parameterTypes.map { it.toString() }

        addInvokeComposableMethod(postTitleMethod, "patch_invokePostTitle", InvokePostTitleFingerprint)

        val selectablePostTitleMethod = ImmutableMethod(
            postTitleMethod.definingClass,
            "patch_selectablePostTitle",
            postTitleMethod.parameters.map { ImmutableMethodParameter(it.type, null, null) },
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(SELECTION_CONTAINER_SMALI_REGISTERS + postTitleParamTypes.size)
        ).toMutable().apply {
            // A clickable consumes the touch-down event, even if it is disabled. A clickable title
            // inside the SelectionContainer would prevent starting a selection with a long press.
            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    move/16 p$POST_TITLE_CLICKABLE_PARAMETER_INDEX, v0
                    ${selectionContainerSmali(postTitleParamTypes, "createPostTitleContent")}
                """
            )
        }
        mutableClassDefBy(postTitleMethod.definingClass).methods.add(selectablePostTitleMethod)

        PostTitleSectionFingerprint.let {
            it.method.apply {
                val callIndex = it.instructionMatches.last().index
                val call = getInstruction<RegisterRangeInstruction>(callIndex)
                val callRegisters = call.startRegister until call.startRegister + call.registerCount
                val freeRegister = findFreeRegister(callIndex, callRegisters.toList())

                addInstructionsWithLabels(
                    callIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->shouldWrapPostTitle()Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :morphe_original
                        invoke-static/range { v${callRegisters.first} .. v${callRegisters.last} }, $selectablePostTitleMethod
                        goto :morphe_skip_original
                        :morphe_original
                        nop
                    """,
                    ExternalLabel("morphe_skip_original", getInstruction(callIndex + 1))
                )
            }
        }

        // endregion

        // region Block collapsing a comment with the touch that starts or clears a text selection.

        CommentClickEventHandlerFingerprint.let {
            it.method.apply {
                val branchIndex = it.instructionMatches[3].index
                val collapsedRegister = getInstruction<OneRegisterInstruction>(branchIndex).registerA
                val freeRegister = findFreeRegister(branchIndex, collapsedRegister)
                val unitField = getInstruction<ReferenceInstruction>(it.instructionMatches.last().index).reference

                addInstructionsWithLabels(
                    branchIndex,
                    """
                        invoke-static/range { v$collapsedRegister .. v$collapsedRegister }, $EXTENSION_CLASS->shouldBlockCommentCollapse(Z)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :morphe_collapse_allowed
                        sget-object v$freeRegister, $unitField
                        return-object v$freeRegister
                        :morphe_collapse_allowed
                        nop
                    """
                )
            }
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
                val index = it.instructionMatches.last().index
                val register = this.getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->adjustSelectionTapSlop(F)F
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region Keep the selection while touching and scrolling, a tap clears it.

        SelectionReleaseFingerprint.let {
            // The extension clears selections by calling the release method.
            it.classDef.apply {
                addPatchInterface(
                    EXTENSION_SELECTION_MANAGER_INTERFACE,
                    "patch_release",
                    """
                        invoke-virtual { p0 }, ${it.method}
                        return-void
                    """
                )
            }

            it.method.addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->onSelectionRelease($EXTENSION_SELECTION_MANAGER_INTERFACE)V"
            )
        }

        SelectionChangedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->onSelectionChanged(${EXTENSION_SELECTION_MANAGER_INTERFACE}Ljava/lang/Object;)V"
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
