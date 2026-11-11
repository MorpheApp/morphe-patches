/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2628
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
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
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = "this"),
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = "this", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT, definingClass = "this", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = "this", location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately()),
    ),
)

/**
 * Constructor of the navigation request, which has the navigation intent and the video to play.
 */
internal fun navigationRequestFingerprint(enumType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(enumType, "L", "L")
)

/**
 * Each implementation of the navigation method. These share no common instructions
 * (some are empty or only delegate), so the request parameter is what identifies them.
 */
internal fun navigationMethodFingerprint(requestType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(requestType)
)
