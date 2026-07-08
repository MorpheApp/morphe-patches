/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.dislikeredirection

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// String is a substring so both `NotificationLikeButtonController` (pre-8.x) and
// `DefaultNotificationLikeButtonController` (8.x+) match.
internal object NotificationLikeButtonControllerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("NotificationLikeButtonController"),
    custom = { method, _ ->
        method.name == "<clinit>"
    }
)

internal object NotificationLikeButtonOnClickListenerFingerprint : Fingerprint(
    classFingerprint = NotificationLikeButtonControllerFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    custom = { method, _ ->
        indexOfMapInstruction(method) >= 0
    }
)

internal fun indexOfMapInstruction(method: Method) =
    method.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_VIRTUAL &&
                reference?.parameterTypes?.size == 3 &&
                reference.parameterTypes[2].toString() == "Ljava/util/Map;"
    }

// Field names are obfuscated in 9.x.
// Class shape is stable: 7 fields, 5 methods, 3 of which are `V(L, Ljava/util/Map;)` interface impls
// and only one dispatches the next-track onClick (invoke-interface V(L)).
internal object DislikeButtonOnClickListenerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    custom = custom@{ method, classDef ->
        if (classDef.fields.count() != 7) return@custom false
        if (classDef.methods.count() != 5) return@custom false

        val interfaceMethodCount = classDef.methods.count { m ->
            AccessFlags.PUBLIC.isSet(m.accessFlags) &&
                    AccessFlags.FINAL.isSet(m.accessFlags) &&
                    m.returnType == "V" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes.last().toString() == "Ljava/util/Map;"
        }
        if (interfaceMethodCount != 3) return@custom false

        val instructions = method.implementation?.instructions ?: return@custom false
        if (instructions.count() < 50) return@custom false

        method.indexOfFirstInstruction {
            val reference = getReference<MethodReference>()
            opcode == Opcode.INVOKE_INTERFACE &&
                    reference?.returnType == "V" &&
                    reference.parameterTypes.size == 1
        } >= 0
    }
)
