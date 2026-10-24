/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.p0Register
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Observe the existing drag transaction; retain native animation and accessibility. */
internal fun installPlaylistReordering(context: BytecodePatchContext, videoType: String) = with(context) {
    val holderType = PlaylistDragBinderFingerprint.matchSingle().originalMethod.definingClass
    val start = playlistDragStartFingerprint(holderType).matchSingle()
    val end = playlistDragEndFingerprint(holderType).matchSingle()
    check(start.originalMethod.parameterTypes.take(2) == end.originalMethod.parameterTypes.take(2)) {
        "Native playlist queue: drag callbacks disagree about their holder/adapter types"
    }

    for (callback in listOf(start, end)) {
        val predicate = callback.instructionMatches.first().instruction as TwoRegisterInstruction
        val receiver = callback.originalMethod.p0Register
        check(setOf(predicate.registerA, predicate.registerB) == setOf(receiver, receiver + 1)) {
            "Native playlist queue: drag notification must be guarded by holder identity"
        }
    }

    val adapterType = start.originalMethod.parameterTypes[1].toString()

    // Native edit events inspect the adapter's wrapper through this row interface.
    // The native server-edit branch additionally requires playlistSetVideoId; our
    // renderer omits it, so normal account edits need no interception or replacement.
    val rowGetter = playlistEditEventsFingerprint(videoType).matchSingle()
        .instructionMatches.single().instruction.getReference<MethodReference>()!!

    val adapter = mutableClassDefBy(adapterType)
    adapter.interfaces.add(REORDER_ITEMS_INTERFACE)
    adapter.addBridgeMethod("patch_getSize", emptyList(), "I", 1, """
        invoke-interface {p0}, Ljava/util/List;->size()I
        move-result v0
        return v0
    """)

    adapter.addBridgeMethod("patch_getQueueRow", listOf("I"), "Ljava/lang/Object;", 2, """
        invoke-interface {p0, p1}, Ljava/util/List;->get(I)Ljava/lang/Object;
        move-result-object v0
        instance-of v1, v0, ${rowGetter.definingClass}
        if-eqz v1, :missing
        check-cast v0, ${rowGetter.definingClass}
        invoke-interface {v0}, $rowGetter
        move-result-object v0
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    // Both callbacks first reject notifications for another holder. Enter on the
    // guarded branch, before start's visual update reuses p0/p1 as drawable locals.
    start.method.addInstructionsAtControlFlowLabel(start.instructionMatches[1].index,
        "invoke-static/range {p1 .. p3}, $EXTENSION_CLASS->onReorderStarted(Ljava/lang/Object;${REORDER_ITEMS_INTERFACE}I)V")
    end.method.addInstructionsAtControlFlowLabel(end.instructionMatches[1].index,
        "invoke-static/range {p1 .. p4}, $EXTENSION_CLASS->onReorderEnded(Ljava/lang/Object;${REORDER_ITEMS_INTERFACE}II)V")
    playlistDragUnbindFingerprint(holderType).matchSingle().method.addInstruction(0,
        "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onReorderHolderUnbound(Ljava/lang/Object;)V")
}
