/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.instanceOf
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The enqueue completion callback stores the manager and the command's insertion target. */
internal fun queueLikeFilterEnqueueCallbackFingerprint(managerType: String) = Fingerprint(
    name = "<init>",
    custom = { method, owner ->
        method.parameterTypes.size == 2 &&
                method.parameterTypes[0].toString() == managerType &&
                owner.fields.any { it.type == managerType } &&
                classDefByOrNull(method.parameterTypes[1].toString())
                    ?.let { AccessFlags.ENUM.isSet(it.accessFlags) } == true
    }
)

/** Enum initialization binds the enqueue target used by the "Play next" action. */
internal fun queueLikeFilterPlayNextTargetFingerprint(targetType: String) = Fingerprint(
    definingClass = targetType,
    name = "<clinit>",
    filters = listOf(
        string("INSERT_AFTER_CURRENT_VIDEO"),
        fieldAccess(definingClass = targetType, type = targetType, opcode = Opcode.SPUT_OBJECT)
    )
)

/** Types referenced by casts, allocations and instanceof checks, in source order. */
internal fun Method.typeReferences(): List<String> =
    findInstructionIndicesReversed(
        anyInstruction(checkCast("L"), newInstance("L"), instanceOf("L"))
    )
        .map { getInstruction(it).getReference<TypeReference>()!!.type }
        .distinct()
