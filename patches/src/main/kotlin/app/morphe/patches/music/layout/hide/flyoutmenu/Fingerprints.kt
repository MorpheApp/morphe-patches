/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.flyoutmenu

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MenuItemFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("toggleMenuItemMutations"),
        // Menu item container the item view is added to.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/widget/FrameLayout;")
    )
)

/**
 * Sets the menu item text and icon. Before 9.44 this is the same method as [MenuItemFingerprint].
 */
internal object MenuItemIconFingerprint : Fingerprint(
    classFingerprint = MenuItemFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"),
        // Menu item renderer.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT),
        // Icon of the menu item renderer.
        methodCall(opcode = Opcode.INVOKE_STATIC, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Icon type.
        fieldAccess(type = "I", opcode = Opcode.IGET, location = MatchAfterImmediately()),
        // Icon type enum.
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("I"), location = MatchAfterImmediately()),
        methodCall(smali = "Landroid/widget/ImageView;->setImageResource(I)V")
    )
)

internal object EndButtonsContainerFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "end_buttons_container")
    )
)
