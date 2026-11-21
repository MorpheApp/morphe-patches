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
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val COMPONENT_HOST_CLASS = "Lcom/facebook/litho/ComponentHost;"
internal const val COMPONENT_TREE_CLASS = "Lcom/facebook/litho/ComponentTree;"
internal const val COMPONENT_TEXT_CONTENT = "Lcom/facebook/litho/TextContent;"

/**
 * Measures the Litho view. The force layout flag is read and cleared,
 * and passed to the component tree that calculates the layout again if set.
 */
internal object LithoViewOnMeasureFingerprint : Fingerprint(
    superclass = COMPONENT_HOST_CLASS,
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
    )
)

/**
 * Sets the root component and the size specs of the component tree, and calculates the layout
 * if they are not compatible with the committed layout, or if the layout is forced.
 * An async layout is calculated on the layout thread of the tree, and then mounted on the main thread.
 */
internal object ComponentTreeSetRootAndSizeSpecFingerprint : Fingerprint(
    definingClass = COMPONENT_TREE_CLASS,
    returnType = "V",
    parameters = listOf("L", "I", "I", "Z", "L", "I", "Ljava/lang/String;", "L", "Z", "Z"),
    filters = listOf(
        string("The layout can't be calculated asynchronously if we need the Size back")
    )
)

/**
 * Unmounts all mounted content of the Litho view. The content is mounted again by the next layout.
 */
internal object LithoViewUnmountAllItemsFingerprint : Fingerprint(
    classFingerprint = LithoViewOnMeasureFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/graphics/Rect;->setEmpty()V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/graphics/Rect;->setEmpty()V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/graphics/Rect;->setEmpty()V"
        )
    )
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

/**
 * The text of the Litho text drawable.
 */
internal object LithoTextDrawableGetTextItemsFingerprint : Fingerprint(
    superclass = "Landroid/graphics/drawable/Drawable;",
    interfaces = listOf(COMPONENT_TEXT_CONTENT),
    name = "getTextItems",
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/CharSequence;"
        ),
        methodCall(
            smali = "Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Draws the text layout of the Litho text drawable.
 */
internal object LithoTextDrawableDrawFingerprint : Fingerprint(
    classFingerprint = LithoTextDrawableGetTextItemsFingerprint,
    name = "draw",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/text/Layout;"
        )
    )
)
