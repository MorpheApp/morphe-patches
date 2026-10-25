/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2628
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object NavigationIntentEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("NEXT"),
        string("PREVIOUS"),
        string("AUTOPLAY"),
        string("AUTONAV"),
        string("JUMP"),
        opcode(Opcode.RETURN_VOID),
    ),
    custom = { _, classDef ->
        classDef.methods.any { it.name == "<init>" && it.parameterTypes.size > 2 }
    },
)

internal object NativeNavigationStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    // Previous video available, next video available, repeat mode, shuffle enabled
    parameters = listOf("Z", "Z", "I", "Z"),
    custom = { _, classDef ->
        // Narrow the match to the state object: four fields and only a constructor
        classDef.fields.count() == 4 && classDef.methods.count() == 1
    },
)
