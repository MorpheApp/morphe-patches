/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.format

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patches.shared.FormatStreamModelToStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object HookAdaptiveFormatsFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    name = "privateHookAdaptiveFormats"
)

internal fun getFormatStreamModelMethodFingerprint(
    formatStreamModelFormatField: FieldReference,
    targetMethod: MethodReference
) = object : Fingerprint(
    classFingerprint = FormatStreamModelToStringFingerprint,
    name = targetMethod.name,
    returnType = targetMethod.returnType,
    filters = listOf(
        fieldAccess(
            reference = formatStreamModelFormatField,
            location = MatchFirst()
        ),
        fieldAccess(
            type = targetMethod.returnType,
            location = MatchAfterImmediately()
        ),
        anyInstruction(
            opcode(
                opcode = Opcode.RETURN,
                location = MatchAfterImmediately()
            ),
            opcode(
                opcode = Opcode.RETURN_OBJECT,
                location = MatchAfterImmediately()
            ),
        )
    )
) {}

internal fun getFormatFingerprint(formatClass: String) = object : Fingerprint(
    definingClass = formatClass,
    name = "<init>"
) {}
