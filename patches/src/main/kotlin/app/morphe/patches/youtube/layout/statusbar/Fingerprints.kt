/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3337
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.statusbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Draws the top bars behind the status bar. 21.29 and older read it with a single
 * accessor method, newer targets read it inline in every class that uses it.
 */
internal object EdgeToEdgeTopBarFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45400535L)
    )
)

/**
 * Colors the status bar like the top bar while it scrolls. Needs the edge to edge top bar.
 * Not present in older targets.
 */
internal object StatusBarScrollColorFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45830993L)
    )
)

/**
 * Shows the view drawn behind the status bar and sets its color.
 */
internal object StatusBarBackgroundShowFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf(),
        filters = listOf(
            methodCall(smali = "Landroid/view/View;->bringToFront()V"),
            methodCall(smali = "Landroid/view/View;->getParent()Landroid/view/ViewParent;"),
            methodCall(smali = "Landroid/view/View;->bringToFront()V")
        )
    ),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->setVisibility(I)V"),
        methodCall(smali = "Landroid/view/View;->setBackgroundColor(I)V")
    )
)

/**
 * Sends a copy of the system window insets to the views that lay out below the status bar,
 * together with the view the insets are read from.
 */
internal object SystemWindowInsetsChangedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/graphics/Rect;-><init>(Landroid/graphics/Rect;)V"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/view/View;",
            location = MatchAfterImmediately()
        )
    )
)
