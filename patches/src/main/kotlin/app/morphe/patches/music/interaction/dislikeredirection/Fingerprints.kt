/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.dislikeredirection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// String is a substring so both `NotificationLikeButtonController` (pre-8.x) and
// `DefaultNotificationLikeButtonController` (8.x+) match.
internal object NotificationLikeButtonOnClickListenerFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        name = "<clinit>",
        returnType = "V",
        parameters = listOf(),
        filters = listOf(
            anyInstruction(
                // 7.29
                string("com/google/android/apps/youtube/music/player/notification/NotificationLikeButtonController"),
                // 8.x+
                string("com/google/android/apps/youtube/music/player/notification/DefaultNotificationLikeButtonController")
            )
        )
    ),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L", "L", "Ljava/util/Map;")
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            returnType = "V",
            parameters = listOf("L")
        )
    )
)

// Field names are obfuscated in 9.x.
// Class shape is stable: 7 fields, 5 methods, 3 of which are `V(L, Ljava/util/Map;)` interface impls
// and only one dispatches the next-track onClick (invoke-interface V(L)).
internal object DislikeButtonOnClickListenerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            returnType = "V",
            parameters = listOf("L")
        )
    ),
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
        return@custom instructions.count() >= 50
    }
)
