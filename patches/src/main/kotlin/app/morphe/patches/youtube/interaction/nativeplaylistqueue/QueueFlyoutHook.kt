/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.youtube.layout.flyout.FeedFlyoutButtonsInitializerFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ACTIONS = "Lapp/morphe/extension/youtube/patches/queue/QueueFlyoutActions;"
private const val MESSAGE = "Lcom/google/protobuf/MessageLite;"

/** Complete the native model, so both list and recycler adapters retain their own item positions. */
internal fun installQueueFlyout(context: BytecodePatchContext) = with(context) {
    val sheets = QueueFlyoutRendererFingerprint.matchAll(2..2)
    val menuTypes = sheets.map {
        it.instructionMatches[0].instruction.getReference<FieldReference>()!!.type
    }.distinct()
    check(menuTypes.size == 1) { "Native queue menus must share their renderer type" }
    val menuType = menuTypes.single()
    val itemFields = sheets.map {
        it.instructionMatches[1].instruction.getReference<FieldReference>()!!
    }.distinctBy { it.toString() }
    check(itemFields.size == 1) { "Native queue menus must share their item list" }
    val resolverType = QueueFlyoutResolverFingerprint.matchSingle().originalClassDef.type
    val popup = queueFlyoutPopupFingerprint(resolverType, menuType, itemFields.single()).matchSingle()

    // The shared factory exposes native icon conversion and the button's model
    // type. Direct sheet clicks can bypass its Runnable, so intercept both native
    // dispatchers before their backend command, retaining their dismissal branch.
    FeedFlyoutButtonsInitializerFingerprint.clearMatch()
    val factory = FeedFlyoutButtonsInitializerFingerprint.instructionMatches
    val iconGetter = factory.first().instruction.getReference<MethodReference>()!!
    val iconField = factory[6].instruction.getReference<FieldReference>()!!
    val iconEnum = factory[7].instruction.getReference<MethodReference>()!!
    val itemType = iconGetter.parameterTypes.single().toString()
    val buttonType = queueFlyoutButtonConstructorFingerprint(
        FeedFlyoutButtonsInitializerFingerprint.originalMethod, itemType
    ).matchSingle().instructionMatches.single().instruction.getReference<MethodReference>()!!.definingClass
    for (sheet in sheets) {
        val click = queueFlyoutClickFingerprint(
            sheet.originalClassDef.type, buttonType, itemType
        ).matchSingle()
        val read = click.instructionMatches.first()
        val item = (read.instruction as OneRegisterInstruction).registerA
        val method = click.method
        val free = method.findFreeRegister(read.index + 1, item)
        check(free <= 15) { "Native queue click icon conversion requires a low register" }
        val dismissal = method.getInstruction(click.instructionMatches[1].index)
        method.addInstructionsAtControlFlowLabel(read.index + 1, """
            if-eqz v$item, :native_queue_command
            invoke-static/range {v$item .. v$item}, $iconGetter
            move-result-object v$free
            if-eqz v$free, :native_queue_command
            iget v$free, v$free, $iconField
            invoke-static/range {v$free .. v$free}, $iconEnum
            move-result-object v$free
            invoke-static/range {v$free .. v$free}, $ACTIONS->onStockQueueItemClick(Ljava/lang/Enum;)Z
            move-result v$free
            if-nez v$free, :native_queue_dismiss
            :native_queue_command
            nop
        """, ExternalLabel("native_queue_dismiss", dismissal))
    }

    val menu = mutableClassDefBy(menuType)
    menu.interfaces.add("${ACTIONS.removeSuffix(";")}\$Menu;")
    menu.addBridgeMethod("patch_parseQueueFlyout", listOf("[B"), MESSAGE, 2, """
        ${parseProtoInstructions(menuType, bytes = "p1", result = "v0", registry = "v1")}
        return-object v0
    """)

    for (sheet in sheets) {
        val read = sheet.instructionMatches.first()
        val register = (read.instruction as OneRegisterInstruction).registerA
        // Only the local renderer is replaced. The sheet's original model and
        // every unrelated item remain intact; null still follows the native branch.
        sheet.method.addInstructions(read.index + 1, """
            invoke-static/range {v$register .. v$register}, $ACTIONS->completeQueueMenu($MESSAGE)$MESSAGE
            move-result-object v$register
            check-cast v$register, $menuType
        """)
    }
    popup.method.addInstructions(0, """
        invoke-static/range {p0 .. p0}, $ACTIONS->completeQueueMenu($MESSAGE)$MESSAGE
        move-result-object p0
        check-cast p0, $menuType
    """)
}
