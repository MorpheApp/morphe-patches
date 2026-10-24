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
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Retain native button validation and dispatch every unrelated command unchanged. */
internal fun installPlaylistRemoval(context: BytecodePatchContext) = with(context) {
    val dispatch = NativeButtonDispatchFingerprint.matchSingle()
    val serviceCall = dispatch.instructionMatches.single()
    val registers = serviceCall.instruction as FiveRegisterInstruction
    val callback = serviceCall.instruction.getReference<MethodReference>()!!
    val endpointType = callback.parameterTypes.first()

    installQueueUndo(this, callback)

    // Reuse the endpoint result register, keeping every live native local intact.
    // Null consumes our local command; otherwise restore the native endpoint type.
    dispatch.method.addInstructionsAtControlFlowLabel(serviceCall.index, """
        invoke-static {v${registers.registerD}, v${registers.registerE}}, $EXTENSION_CLASS->onQueueMenuCommand(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)Lcom/google/protobuf/MessageLite;
        move-result-object v${registers.registerD}
        if-nez v${registers.registerD}, :native_dispatch
        return-void

        :native_dispatch
        check-cast v${registers.registerD}, $endpointType
    """)
}

/** Preserve native swipe drawing when an attached playing row becomes removable. */
internal fun installPlaylistSwipeBackground(context: BytecodePatchContext, listener: FieldReference) = with(context) {
    val attached = PlaylistSwipeAttachedFingerprint.matchSingle()
    val foregroundStore = attached.instructionMatches.first()
    val backgroundRead = attached.instructionMatches[1].instruction as FiveRegisterInstruction
    val backgroundStore = attached.instructionMatches.last()
    val foregroundRegisters = foregroundStore.instruction as TwoRegisterInstruction
    val backgroundRegisters = backgroundStore.instruction as TwoRegisterInstruction
    check(foregroundRegisters.registerA == backgroundRead.registerC &&
            foregroundRegisters.registerB == backgroundRegisters.registerB) {
        "Native playlist queue: swipe drawable must belong to the attached foreground"
    }

    val foreground = foregroundStore.instruction.getReference<FieldReference>()!!
    val cachedBackground = backgroundStore.instruction.getReference<FieldReference>()!!
    val swipe = mutableClassDefBy(attached.originalClassDef.type)
    swipe.interfaces.add(SWIPE_BACKGROUND_INTERFACE)

    // Before attachment there is no foreground yet; the native attachment
    // callback will initialize both fields normally. Never replace its drawable.
    swipe.addBridgeMethod("patch_refreshQueueSwipeBackground", emptyList(), "V", 1, """
        iget-object v0, p0, $foreground
        if-eqz v0, :done
        invoke-virtual {v0}, Landroid/view/View;->getBackground()Landroid/graphics/drawable/Drawable;
        move-result-object v0
        iput-object v0, p0, $cachedBackground

        :done
        return-void
    """)

    val update = PlaylistRowBackgroundFingerprint.matchSingle()
    val holder = mutableClassDefBy(update.originalClassDef.type)
    val swipeField = PlaylistRowSwipeLayoutFingerprint.matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!
    val bridge = holder.addBridgeMethod("patch_refreshQueueSwipeBackground", emptyList(), "V", 2, """
        iget-object v0, p0, $listener
        iget-object v1, p0, $swipeField
        invoke-static {v0, v1}, $EXTENSION_CLASS->onSwipeBackgroundChanged($ROW_CLICK_INTERFACE$SWIPE_BACKGROUND_INTERFACE)V
        return-void
    """, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value)

    // Read the receiver from the foreground load, while it is still live. The
    // native method later reuses p0 for a color, so its final return is unsafe.
    val receiver = (update.instructionMatches.first().instruction as TwoRegisterInstruction).registerB
    update.method.addInstruction(update.instructionMatches[1].index + 1,
        "invoke-direct/range {v$receiver .. v$receiver}, $bridge")
}
