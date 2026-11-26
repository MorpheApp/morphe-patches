/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5217
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.collapseall

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CollapseAllCommentsPatch;"
private const val COMMENT_TREE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/CollapseAllCommentsPatch$CommentTree;"

/**
 * Compose group key of the added button. Any fixed value works.
 */
private const val BUTTON_GROUP_KEY = 0x0b1c0055

@Suppress("unused")
val collapseAllCommentsPatch = patch(
    name = "Collapse all comments",
    description = "Adds an option to show a button next to the comment count of a post that collapses or expands all comments. This patch works with Reddit 2026.40.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        versionCheckPatch
    )

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Collapse all comments' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        // Remember the comment click handler of the post page action bar,
        // so the comment button of the post page can be told apart from the feed.
        PostActionBarFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->" +
                    "registerPostCommentsClick(Lkotlin/jvm/functions/Function0;)V"
        )

        addCollapseAllButton()

        hookCommentTree()

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

/**
 * Draws the button after each comment button of the action bar.
 */
private fun addCollapseAllButton() {
    val buttonClass = ActionBarCommentButtonFingerprint.classDef
    val commentTestTagsField = ActionBarCommentButtonFingerprint.instructionMatches[1].getFieldAccessed()

    // Reads the comment button test tags, then draws the comment button with them.
    // There is one draw method for the default action bar and one for the refresh variant.
    val drawCommentButtonFilters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT, reference = commentTestTagsField),
        methodCall(
            definingClass = "this",
            returnType = "V",
            location = MatchAfterWithin(10)
        )
    )

    // The comment button of the default action bar is followed by a group of the next button.
    val composerMatch = Fingerprint(
        classFingerprint = ActionBarCommentButtonFingerprint,
        filters = drawCommentButtonFilters + listOf(
            // startReplaceGroup(key)
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                parameters = listOf("I"),
                returnType = "V",
                location = MatchAfterWithin(2)
            ),
            // endReplaceGroup()
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                parameters = listOf("Z"),
                returnType = "V"
            )
        )
    ).match()
    val startGroup = composerMatch.instructionMatches[2].getMethodCalled()
    val endGroup = composerMatch.instructionMatches[3].getMethodCalled()

    val drawButtonMethods = mutableMapOf<MethodReference, MethodReference>()

    Fingerprint(
        classFingerprint = ActionBarCommentButtonFingerprint,
        filters = drawCommentButtonFilters
    ).matchAll().forEach { match ->
        val drawCall = match.instructionMatches[1]
        val drawButton = drawCall.getMethodCalled()
        val drawButtonMethod = drawButtonMethods.getOrPut(drawButton) {
            val (registers, smali) = getDrawButtonSmali(drawButton, startGroup, endGroup)
            ImmutableMethod(
                buttonClass.type,
                "patch_drawCollapseAllButton",
                drawButton.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) },
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(registers)
            ).toMutable().apply {
                addInstructionsWithLabels(0, smali)
            }.also(buttonClass.methods::add)
        }

        match.method.apply {
            // Same arguments as the comment button, so the call can use the same registers.
            val call = getInstruction<RegisterRangeInstruction>(drawCall.index)
            val lastRegister = call.startRegister + call.registerCount - 1
            addInstruction(
                drawCall.index + 1,
                "invoke-static/range { v${call.startRegister} .. v$lastRegister }, $drawButtonMethod"
            )
        }
    }
}

/**
 * Smali of a method with the same parameters as [drawButton], that draws the collapse all button
 * if the comment button it is given is the one of the post page.
 *
 * The parameters of [drawButton] are: action, icon, ..., composer, changed flags, optional default flags.
 * The button is drawn with the same arguments as the comment button, except for its own action and
 * icon, no label, and no changed flags (Compose then compares all arguments).
 *
 * @return The number of registers and the smali.
 */
private fun getDrawButtonSmali(
    drawButton: MethodReference,
    startGroup: MethodReference,
    endGroup: MethodReference
): Pair<Int, String> {
    val actionConstructor = ActionBarActionConstructorFingerprint.method
    val onClickField = ActionBarActionConstructorFingerprint.instructionMatches.first().getFieldAccessed()
    val labelType = actionConstructor.parameterTypes[1].toString()
    val iconType = IconToStringFingerprint.classDef.type
    val iconFlagField = IconConstructorFingerprint.instructionMatches[0].getFieldAccessed()
    val iconSizeField = IconConstructorFingerprint.instructionMatches[1].getFieldAccessed()

    val parameterTypes = drawButton.parameterTypes.map { it.toString() }
    val composerIndex = parameterTypes.indexOfLast { it.startsWith("L") }
    val changedIndex = composerIndex + 1

    // v0: composer, v1: scratch, v2 and up: arguments of the draw call.
    // The action constructor call needs v2 to v10.
    val localRegisters = maxOf(2 + parameterTypes.size, 11)

    val arguments = parameterTypes.withIndex().drop(2).joinToString("\n") { (index, type) ->
        val register = "v${2 + index}"
        when {
            index == changedIndex || type == labelType -> "const/4 $register, 0x0"
            type.startsWith("L") -> "move-object/from16 $register, p$index"
            else -> "move/from16 $register, p$index"
        }
    }

    return localRegisters + parameterTypes.size to """
        move-object/from16 v0, p$composerIndex
        check-cast v0, ${startGroup.definingClass}
        const v1, $BUTTON_GROUP_KEY
        invoke-virtual { v0, v1 }, $startGroup

        move-object/from16 v1, p0
        iget-object v1, v1, $onClickField
        invoke-static { v1 }, $EXTENSION_CLASS->showCollapseAllButton(Lkotlin/jvm/functions/Function0;)Z
        move-result v1
        if-eqz v1, :end

        # Action: no label, default modifier, enabled.
        new-instance v2, ${actionConstructor.definingClass}
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllDescription()Ljava/lang/String;
        move-result-object v3
        const/4 v4, 0x0
        const/4 v5, 0x0
        const/4 v6, 0x1
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllClick()Lkotlin/jvm/functions/Function0;
        move-result-object v7
        const-wide/16 v8, 0x0
        const/4 v10, 0x6
        invoke-direct/range { v2 .. v10 }, $actionConstructor

        # Icon with the size and flag of the comment icon.
        move-object/from16 v1, p1
        new-instance v3, $iconType
        iget-wide v4, v1, $iconSizeField
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllIcon()I
        move-result v6
        iget-boolean v7, v1, $iconFlagField
        invoke-direct { v3, v4, v5, v6, v7 }, $iconType-><init>(JIZ)V

        $arguments
        invoke-static/range { v2 .. v${1 + parameterTypes.size} }, $drawButton

        :end
        const/4 v1, 0x0
        invoke-virtual { v0, v1 }, $endGroup
        return-void
    """
}

/**
 * Lets the extension read and change the comment tree of the post.
 */
private fun hookCommentTree() {
    val storeClass = CommentTreeIndexOfFingerprint.classDef
    val applyOperation = CommentTreeApplyOperationFingerprint.method

    CommentTreeIndexOfFingerprint.instructionMatches.let { matches ->
        val stateField = matches[0].getFieldAccessed()
        val getLoadedState = matches[1].getMethodCalled()
        val visibleIdsField = matches[4].getFieldAccessed()

        storeClass.addPatchInterface(
            COMMENT_TREE_INTERFACE,
            "patch_getVisibleCommentIds",
            """
                iget-object v0, p0, $stateField
                invoke-static { v0 }, $getLoadedState
                move-result-object v0
                if-eqz v0, :not_loaded
                iget-object v0, v0, $visibleIdsField
                :not_loaded
                return-object v0
            """
        )
    }

    storeClass.addPatchInterface(
        COMMENT_TREE_INTERFACE,
        "patch_getComment",
        CommentTreeGetCommentFingerprint.method
    )

    mapOf(
        "patch_collapse" to CollapseCommentOperationToStringFingerprint,
        "patch_expand" to ExpandCommentOperationToStringFingerprint
    ).forEach { (methodName, operationFingerprint) ->
        val operationClass = operationFingerprint.classDef.type
        storeClass.addPatchInterface(
            COMMENT_TREE_INTERFACE,
            methodName,
            """
                new-instance v0, $operationClass
                invoke-direct { v0, p1 }, $operationClass-><init>(Ljava/lang/String;)V
                invoke-static { p0, v0 }, $applyOperation
                return-void
            """
        )
    }

    // The tree that last applied an operation is the one of the post that is open.
    applyOperation.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setCommentTree($COMMENT_TREE_INTERFACE)V"
    )
}
