/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.youtube.layout.flyout.FeedFlyoutButtonsInitializerFingerprint
import app.morphe.patches.youtube.layout.hide.general.ActionBarMenuBuilderFingerprint
import app.morphe.patcher.util.cloneParameters
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val ACTIONS = "Lapp/morphe/extension/youtube/patches/queue/QueueFlyoutActions;"
private const val MESSAGE = "Lcom/google/protobuf/MessageLite;"

/** Complete the native model, so both list and recycler adapters retain their own item positions. */
internal fun installQueueFlyout() {
    val sheets = QueueFlyoutRendererFingerprint.matchAll(2..2)
    val menuTypes = sheets.map {
        it.instructionMatches[0].fieldReference.type
    }.distinct()
    check(menuTypes.size == 1) { "Native queue menus must share their renderer type" }
    val menuType = menuTypes.single()
    val itemFields = sheets.map {
        it.instructionMatches[1].fieldReference
    }.distinctBy { it.toString() }
    check(itemFields.size == 1) { "Native queue menus must share their item list" }
    val resolverType = QueueFlyoutResolverFingerprint.originalClassDef.type
    val popup = queueFlyoutPopupFingerprint(resolverType, menuType, itemFields.single()).match()

    // The shared factory exposes native icon conversion and the button's model
    // type. Direct sheet clicks can bypass its Runnable, so intercept both native
    // dispatchers before their backend command, retaining their dismissal branch.
    val factory = FeedFlyoutButtonsInitializerFingerprint.instructionMatches
    val iconGetter = factory.first().methodReference
    val iconField = factory[6].fieldReference
    val iconEnum = factory[7].methodReference
    val itemType = iconGetter.parameterTypes.single().toString()
    val buttonType = queueFlyoutButtonConstructorFingerprint(
        FeedFlyoutButtonsInitializerFingerprint.originalMethod, itemType
    ).instructionMatches.single().methodReference.definingClass
    for (sheet in sheets) {
        val click = queueFlyoutClickFingerprint(
            sheet.originalClassDef.type, buttonType, itemType
        ).match()
        val read = click.instructionMatches.first()
        val item = (read.instruction as OneRegisterInstruction).registerA
        val method = click.method
        val free = method.findFreeRegister(read.index + 1, item)
        check(free <= 15) { "Native queue click icon conversion requires a low register" }
        val dismissal = method.getInstruction(click.instructionMatches[1].index)
        method.addInstructionsAtControlFlowLabel(
            read.index + 1,
            """
                if-eqz v$item, :native_queue_command
                invoke-static/range {v$item .. v$item}, $ACTIONS->onPlaylistQueueItemClick($MESSAGE)Z
                move-result v$free
                if-nez v$free, :native_queue_dismiss
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
            """,
            ExternalLabel("native_queue_dismiss", dismissal)
        )
    }

    val menu = mutableClassDefBy(menuType)
    menu.addPatchInterface(
        $$"$${ACTIONS.removeSuffix(";")}$Menu;",
        "patch_parseQueueFlyout",
        """
            ${parseProtoInstructions(menuType, bytes = "p1", result = "v0", registry = "v1")}
            return-object v0
        """
    )

    for (sheet in sheets) {
        val read = sheet.instructionMatches.first()
        val register = (read.instruction as OneRegisterInstruction).registerA
        // Only the local renderer is replaced. The sheet's original model and
        // every unrelated item remain intact; null still follows the native branch.
        sheet.method.addInstructions(
            read.index + 1,
            """
                invoke-static/range {v$register .. v$register}, $ACTIONS->completeQueueMenu($MESSAGE)$MESSAGE
                move-result-object v$register
                check-cast v$register, $menuType
            """
        )
    }

    popup.method.addInstructions(
        0,
        """
            invoke-static/range {p0 .. p0}, $ACTIONS->completeQueueMenu($MESSAGE)$MESSAGE
            move-result-object p0
            check-cast p0, $menuType
        """
    )
    installPlaylistPageToolbar(menuType, itemFields.single())
}

private fun installPlaylistPageToolbar(menuType: String, items: FieldReference) {
    // This is an optional surface. Older builds may use the legacy playlist header.
    val factory = PlaylistPageHeaderFactoryFingerprint.matchAll(0..1).singleOrNull() ?: return
    val headerType = factory.instructionMatches.last().instruction.getReference<TypeReference>()!!.type
    val presenter = factory.originalMethod.implementation!!.instructions
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "<init>" && it.parameterTypes.lastOrNull() == headerType }
        .map { it.definingClass }.distinct().singleOrNull() ?: return
    val parent = classDefByOrNull(presenter)?.superclass ?: return
    val menus = listOf(presenter, parent).mapNotNull {
        playlistPageToolbarFingerprint(it, items).matchAll(0..1).singleOrNull()
    }
    if (menus.size != 2) {
        return
    }
    val rowType = ActionBarMenuBuilderFingerprint.originalMethod.implementation!!.instructions
        .mapNotNull { it.getReference<MethodReference>() }
        .filter {
            it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/MenuItem;", "Landroid/content/Context;")
        }.map { it.definingClass }.distinct().singleOrNull() ?: return
    val callback = playlistPageToolbarClickFingerprint(rowType).matchAll(0..Int.MAX_VALUE)
        .map { it.instructionMatches.last().methodReference }.distinctBy { it.toString() }
        .singleOrNull() ?: return
    val commandType = NativeButtonDispatchFingerprint.instructionMatches.single()
        .methodReference.parameterTypes.first().toString()

    fun isToolbarAction(type: String): Boolean =
        type == rowType || classDefByOrNull(type)?.interfaces?.any(::isToolbarAction) == true

    val actions = playlistPageToolbarCommandFingerprint(callback, commandType, ::isToolbarAction)
        .matchAll(0..Int.MAX_VALUE)
    if (actions.isEmpty()) {
        return
    }
    for (action in actions) {
        val command = action.instructionMatches.first().fieldReference
        // The modern direct action may use both of its input registers as locals.
        val method = if (action.originalMethod.implementation!!.registerCount == 2) {
            action.method.cloneParameters()
        } else {
            action.method
        }
        method.addInstructionsAtControlFlowLabel(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $command
                invoke-static {v0}, $ACTIONS->onPlaylistPageQueueCommand($MESSAGE)Z
                move-result v0
                if-eqz v0, :stock_page_toolbar
                const/4 v0, 0x1
                return v0
                :stock_page_toolbar
                nop
            """
        )
    }
    for (menu in menus) {
        val read = menu.instructionMatches.last()
        val source = read.getInstruction<TwoRegisterInstruction>().registerB
        menu.method.addInstructionsAtControlFlowLabel(
            read.index,
            """
                invoke-static/range {v$source .. v$source}, $ACTIONS->preparePlaylistPageMenu($MESSAGE)$MESSAGE
                move-result-object v$source
                check-cast v$source, $menuType
            """
        )
    }
}
