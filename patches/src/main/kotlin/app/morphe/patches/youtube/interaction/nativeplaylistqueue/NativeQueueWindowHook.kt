/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4076
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Keep owned queue navigation independent of the newer player's watch-history window. */
internal fun installNativeQueueWindow(
    context: BytecodePatchContext,
    backend: FieldReference,
    enumType: String,
    navigate: MethodReference,
    canNavigate: MethodReference
) = with(context) {
    val requestType = canNavigate.parameterTypes.single().toString()
    val capability = directQueueBackendAvailabilityFingerprint(backend, requestType).originalMethod
    val intent = queueNavigationIntentFieldFingerprint(requestType, enumType)
        .instructionMatches.single().fieldReference
    val owner = mutableClassDefBy(backend.definingClass)
    owner.interfaces.add(DIRECT_NAVIGATOR_INTERFACE)
    owner.addBridgeMethod(
        "patch_getQueueNavigationIntent",
        listOf("Ljava/lang/Object;"),
        "Ljava/lang/Enum;",
        1,
        """
            check-cast p1, $requestType
            iget-object v0, p1, $intent
            return-object v0
        """
    )
    owner.addBridgeMethod(
        "patch_canNavigateNativeQueue",
        listOf("Ljava/lang/Object;"),
        "Z",
        1,
        """
            check-cast p1, $requestType
            invoke-direct {p0, p1}, $capability
            move-result v0
            return v0
        """
    )

    // This native check retains backend restrictions (including co-watch), but
    // does not require a previous/next entry in the separate history window.
    val availability = playlistServiceCanNavigateFingerprint(backend.definingClass, requestType).method
    val result = availability.findFreeRegister(0, availability.p0Register, availability.p0Register + 1)
    availability.addInstructionsAtControlFlowLabel(
        0,
        """
            invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->nativeQueueNavigationAvailability(${DIRECT_NAVIGATOR_INTERFACE}Ljava/lang/Object;)I
            move-result v$result
            if-ltz v$result, :native_window
            return v$result

            :native_window
            nop
        """
    )

    // Native dispatch commits the queue target before querying availability a
    // second time. That commit can move selection to the first/last item. Keep
    // the already accepted descriptor on the native load path at that boundary.
    // Preserve original parameters: the large dispatcher also reuses its locals.
    val dispatch = directQueueNavigateFingerprint(backend.definingClass, requestType).method.cloneParameters()
    check(dispatch.toString() == navigate.toString()) {
        "Native playlist queue: window dispatch must be the bridged native dispatcher"
    }
    // Shared navigation patches can prepend guards. Inspect current instructions
    // after cloning instead of reusing indexes from an earlier fingerprint match.
    val code = dispatch.implementation!!.instructions.toList()
    val gates = code.indices.filter { code[it].getReference<MethodReference>()?.toString() == canNavigate.toString() }
    check(gates.size == 2) { "Native playlist queue: expected pre-commit and post-commit window checks" }
    val gate = gates.last()
    val descriptorType = NativeQueueDescriptorFingerprint.originalMethod.returnType
    val commit = (0 until gate).single { index ->
        code[index].getReference<MethodReference>()?.let {
            it.definingClass == backend.type && it.parameterTypes.map { type -> type.toString() } == listOf(requestType)
                    && it.returnType == descriptorType
        } == true
    }
    check(code[commit + 1].opcode == Opcode.MOVE_RESULT_OBJECT && code[gate + 1].opcode == Opcode.MOVE_RESULT &&
            code[gate - 3].opcode == Opcode.IF_EQZ &&
            code[gate - 2].getReference<FieldReference>() == backend &&
            code[gate - 1].opcode == Opcode.IF_EQZ && code[gate + 2].opcode == Opcode.IF_EQZ) {
        "Native playlist queue: committed target and backend must be guarded before the window recheck"
    }
    val descriptorGuard = code[gate - 3] as OneRegisterInstruction
    val backendRead = code[gate - 2] as TwoRegisterInstruction
    val backendGuard = code[gate - 1] as OneRegisterInstruction
    val computed = (code[gate + 1] as OneRegisterInstruction).registerA
    check((code[commit + 1] as OneRegisterInstruction).registerA == descriptorGuard.registerA &&
            backendRead.registerA == backendGuard.registerA &&
            (code[gate + 2] as OneRegisterInstruction).registerA == computed) {
        "Native playlist queue: post-commit guards must inspect the native target, backend and result"
    }
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    fun target(index: Int) = addresses[index] + (code[index] as OffsetInstruction).codeOffset
    check(target(gate - 3) == target(gate - 1) && target(gate - 1) == target(gate + 2)) {
        "Native playlist queue: rejected targets must retain the original history fallback"
    }
    dispatch.addInstructionsAtControlFlowLabel(
        gate + 2,
        """
            if-nez v$computed, :native_target
            invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->retainCommittedQueueNavigation(${DIRECT_NAVIGATOR_INTERFACE}Ljava/lang/Object;)Z
            move-result v$computed

            :native_target
            nop
        """
    )
}
