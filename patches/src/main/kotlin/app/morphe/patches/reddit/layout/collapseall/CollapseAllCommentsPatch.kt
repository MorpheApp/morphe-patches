/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5217
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.collapseall

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CollapseAllCommentsPatch;"
private const val COMMENT_TREE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/CollapseAllCommentsPatch$CommentTree;"

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

        hookPostActionBar()
        hookPillAndDrawSites()
        hookCommentTree()

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun hookPostActionBar() {
    PostActionBarFingerprint.method.addInstruction(
        0,
        "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->registerPostCommentsClick(Lkotlin/jvm/functions/Function0;)V"
    )
}

private fun getPillMethodSmali(
    composerClass: String,
    startGroup: Method,
    endGroup: Method,
    pillMethod: Method
): String {
    val onClickField = ActionBarActionConstructorFingerprint.instructionMatches.first().getFieldAccessed()
    val iconFlagField = IconConstructorFingerprint.instructionMatches[0].getFieldAccessed()
    val iconSizeField = IconConstructorFingerprint.instructionMatches[1].getFieldAccessed()
    val actionType = ActionBarActionToStringFingerprint.classDef.type
    val iconType = IconToStringFingerprint.classDef.type

    return """
        move-object/from16 v0, p6
        check-cast v0, $composerClass
        const v1, 0x0b1c0055
        invoke-virtual { v0, v1 }, $startGroup
        move-object/from16 v1, p0
        iget-object v1, v1, $onClickField
        invoke-static { v1 }, $EXTENSION_CLASS->showCollapseAllButton(Lkotlin/jvm/functions/Function0;)Z
        move-result v1
        if-eqz v1, :end
        move-object/from16 v1, p1
        iget-wide v2, v1, $iconSizeField
        iget-boolean v5, v1, $iconFlagField
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllIcon()I
        move-result v4
        new-instance v10, $iconType
        invoke-direct { v10, v2, v3, v4, v5 }, $iconType-><init>(JIZ)V
        new-instance v1, $actionType
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllDescription()Ljava/lang/String;
        move-result-object v2
        const/4 v3, 0x0
        const/4 v4, 0x0
        const/4 v5, 0x1
        invoke-static { }, $EXTENSION_CLASS->getCollapseAllClick()Lkotlin/jvm/functions/Function0;
        move-result-object v6
        const-wide/16 v7, 0x0
        const/4 v9, 0x6
        invoke-direct/range { v1 .. v9 }, ${ActionBarActionConstructorFingerprint.method}
        move-object v2, v10
        move-object/from16 v3, p2
        move-object/from16 v4, p3
        const/4 v5, 0x0
        const/4 v6, 0x0
        move-object/from16 v7, p6
        const/4 v8, 0x0
        invoke-static/range { v1 .. v8 }, $pillMethod
        :end
        const/4 v1, 0x0
        invoke-virtual { v0, v1 }, $endGroup
        return-void
    """
}

private fun addCollapseAllPillMethod(
    pillClass: MutableClass,
    pillMethod: Method,
    composerClass: String,
    startGroup: Method,
    endGroup: Method
) {
    val smali = getPillMethodSmali(composerClass, startGroup, endGroup, pillMethod)
    val newMethod = ImmutableMethod(
        pillClass.type,
        "patch_drawCollapseAllPill",
        pillMethod.parameters.map { ImmutableMethodParameter(it.type, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(20)
    ).toMutable().apply {
        addInstructionsWithLabels(0, smali)
    }
    pillClass.methods.add(newMethod)
}

private fun injectPillDrawSites(
    matches: Collection<Match>,
    pillMethod: Method,
    pillClass: MutableClass
) {
    val params = pillMethod.parameters.joinToString("") { it.type }
    matches.groupBy { match -> match.method }.forEach { (method, methodMatches) ->
        methodMatches.sortedByDescending { it.instructionMatches[1].index }.forEach { match ->
            val pillCallMatch = match.instructionMatches[1]
            val pillCallInstruction = method.getInstruction<RegisterRangeInstruction>(pillCallMatch.index)
            val startRegister = pillCallInstruction.startRegister
            val endRegister = startRegister + pillCallInstruction.registerCount - 1
            method.addInstruction(
                pillCallMatch.index + 1,
                "invoke-static/range { v$startRegister .. v$endRegister }, ${pillClass.type}->patch_drawCollapseAllPill($params)V"
            )
        }
    }
}

private fun hookPillAndDrawSites() {
    val commentSlotField = ActionBarCommentButtonFingerprint.instructionMatches[1].getFieldAccessed()
    val actionType = ActionBarActionToStringFingerprint.classDef.type
    val iconType = IconToStringFingerprint.classDef.type

    val pillClass = ActionBarCommentButtonFingerprint.classDef
    val pillMethod = Fingerprint(
        classFingerprint = ActionBarCommentButtonFingerprint,
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf(actionType, iconType, "L", "L", "L", "L", "L", "I")
    ).method

    val drawSitesFingerprint = Fingerprint(
        definingClass = pillClass.type,
        filters = listOf(
            fieldAccess(opcode = Opcode.SGET_OBJECT, reference = commentSlotField),
            methodCall(reference = pillMethod, location = MatchAfterWithin(6))
        )
    )

    val matches = drawSitesFingerprint.matchAll()
    if (matches.isEmpty()) {
        throw PatchException("Could not find comment pill draw sites")
    }

    val composerFingerprint = Fingerprint(
        definingClass = pillClass.type,
        filters = listOf(
            methodCall(reference = pillMethod),
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                parameters = listOf("I"),
                returnType = "V",
                location = MatchAfterWithin(2)
            ),
            methodCall(
                opcode = Opcode.INVOKE_VIRTUAL,
                parameters = listOf("Z"),
                returnType = "V"
            )
        )
    )

    val composerMatch = composerFingerprint.matchAll().first()
    val startGroup = composerMatch.instructionMatches[1].getMethodCalled()
    val endGroup = composerMatch.instructionMatches[2].getMethodCalled()
    if (startGroup.definingClass != endGroup.definingClass) {
        throw PatchException("Composer startGroup and endGroup defining classes do not match: ${startGroup.definingClass} != ${endGroup.definingClass}")
    }

    addCollapseAllPillMethod(pillClass, pillMethod, startGroup.definingClass, startGroup, endGroup)
    injectPillDrawSites(matches, pillMethod, pillClass)
}

private fun hookCommentTree() {
    val storeClass = CommentTreeIndexOfFingerprint.classDef
    val flowField = CommentTreeIndexOfFingerprint.instructionMatches[0].getFieldAccessed()
    val bMethod = CommentTreeIndexOfFingerprint.instructionMatches[1].getMethodCalled()
    val listField = CommentTreeIndexOfFingerprint.instructionMatches[4].getFieldAccessed()

    storeClass.addPatchInterface(
        COMMENT_TREE_INTERFACE,
        "patch_getVisibleCommentIds",
        """
            iget-object v0, p0, $flowField
            invoke-static { v0 }, $bMethod
            move-result-object v0
            if-eqz v0, :none
            iget-object v0, v0, $listField
            :none
            return-object v0
        """
    )

    storeClass.addPatchInterface(
        COMMENT_TREE_INTERFACE,
        "patch_getComment",
        CommentTreeGetCommentFingerprint.method
    )

    val collapseClass = CollapseCommentOperationFingerprint.classDef.type
    storeClass.addPatchInterface(
        COMMENT_TREE_INTERFACE,
        "patch_collapse",
        """
            new-instance v0, $collapseClass
            invoke-direct { v0, p1 }, $collapseClass-><init>(Ljava/lang/String;)V
            invoke-static { p0, v0 }, ${CommentTreeApplyOperationFingerprint.method}
            return-void
        """
    )

    val expandClass = ExpandCommentOperationFingerprint.classDef.type
    storeClass.addPatchInterface(
        COMMENT_TREE_INTERFACE,
        "patch_expand",
        """
            new-instance v0, $expandClass
            invoke-direct { v0, p1 }, $expandClass-><init>(Ljava/lang/String;)V
            invoke-static { p0, v0 }, ${CommentTreeApplyOperationFingerprint.method}
            return-void
        """
    )

    CommentTreeApplyOperationFingerprint.method.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setCommentTree(Ljava/lang/Object;)V"
    )
}
