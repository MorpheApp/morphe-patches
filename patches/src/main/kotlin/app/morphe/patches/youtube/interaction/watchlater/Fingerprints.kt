/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.watchlater

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.QueueSaveEditErrorFingerprint
import com.android.tools.smali.dexlib2.Opcode

/**
 * Touch listener of the drag handle of a list row.
 */
internal object DragHandleTouchListenerFingerprint : Fingerprint(
    interfaces = listOf("Landroid/view/View\$OnTouchListener;"),
    name = "<init>",
    parameters = listOf("Landroid/view/View;", "L", "L", "Landroid/os/Handler;", "I"),
    filters = listOf(
        methodCall("Landroid/view/ViewConfiguration;->getScaledTouchSlop()I")
    )
)

/**
 * Litho is not obfuscated and the method overrides the framework.
 */
internal object ComponentHostInterceptTouchFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/litho/ComponentHost;",
    name = "onInterceptTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;")
)

/**
 * Constructor of the playlist edit callback, which keeps the playlist id and the edit actions.
 */
internal object PlaylistEditCallbackConstructorFingerprint : Fingerprint(
    classFingerprint = QueueSaveEditErrorFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = "Ljava/lang/String;"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        )
    )
)
