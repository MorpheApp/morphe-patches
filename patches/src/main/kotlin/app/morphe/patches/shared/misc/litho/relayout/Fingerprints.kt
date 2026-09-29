/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.relayout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val COMPONENT_HOST_CLASS = "Lcom/facebook/litho/ComponentHost;"
internal const val COMPONENT_TREE_CLASS = "Lcom/facebook/litho/ComponentTree;"

/**
 * Measures the Litho view. The force layout flag is read and cleared,
 * and passed to the component tree that calculates the layout again if set.
 */
internal object LithoViewOnMeasureFingerprint : Fingerprint(
    name = "onMeasure",
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterImmediately()
        ),
        methodCall(
            definingClass = COMPONENT_TREE_CLASS,
            returnType = "V",
            parameters = listOf("I", "I", "[I", "Z"),
            location = MatchAfterWithin(20)
        )
    ),
    custom = { _, classDef ->
        classDef.superclass == COMPONENT_HOST_CLASS
    }
)

/**
 * Mounts a Litho text. The text layout and the text are set on the text drawable,
 * which is a drawable that implements TextContent.
 */
internal object LithoTextMountFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;", "L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/text/Layout;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/CharSequence;",
            location = MatchAfterWithin(3)
        )
    )
)
