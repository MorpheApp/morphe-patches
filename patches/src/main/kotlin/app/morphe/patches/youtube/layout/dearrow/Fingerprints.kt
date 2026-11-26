/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.dearrow

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patches.shared.misc.litho.relayout.LithoTextDrawableGetTextItemsFingerprint

/**
 * Checks if the Litho text drawable handles a touch event, which is only if the event is inside
 * the bounds of the text: if a clickable span can be tapped, or a long clickable span is pressed.
 */
internal object LithoTextDrawableShouldHandleTouchEventFingerprint : Fingerprint(
    classFingerprint = LithoTextDrawableGetTextItemsFingerprint,
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall(
            definingClass = "this",
            parameters = listOf("Landroid/view/MotionEvent;"),
            returnType = "Z"
        ),
        methodCall(
            definingClass = "this",
            parameters = listOf("Landroid/view/MotionEvent;"),
            returnType = "Z",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Handles a touch event of the Litho text drawable, and taps the clickable span at the event.
 */
internal object LithoTextDrawableOnTouchEventFingerprint : Fingerprint(
    classFingerprint = LithoTextDrawableGetTextItemsFingerprint,
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;", "Landroid/view/View;"),
    filters = listOf(
        methodCall(smali = "Landroid/text/style/ClickableSpan;->onClick(Landroid/view/View;)V")
    )
)
