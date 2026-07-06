/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.litho.context

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val IDENTIFIER_PROPERTY = ", identifierProperty="
internal const val STRING_BUILDER_TYPE = "Ljava/lang/StringBuilder;"
internal const val STRING_TYPE = "Ljava/lang/String;"

internal object ConversionContextToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = STRING_TYPE,
    strings = listOf(
        "ConversionContext{", // Partial string match.
        ", widthConstraint=",
        ", heightConstraint=",
        ", templateLoggerFactory=",
        ", rootDisposableContainer=",
        IDENTIFIER_PROPERTY
    )
)

/**
 * Matches the method returning the identifier.
 * Caller must supply the dynamic class type and resolved identifier field.
 */
internal fun conversionContextIdentifierFingerprint(
    conversionContextClassType: String,
    identifierField: FieldReference) = Fingerprint(
    definingClass = conversionContextClassType,
    parameters = listOf(),
    returnType = STRING_TYPE,
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = identifierField
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Matches the method returning the string builder.
 * Caller must supply the dynamic class type and resolved string builder field.
 */
internal fun conversionContextStringBuilderFingerprint(
    conversionContextClassType: String,
    stringBuilderField: FieldReference) = Fingerprint(
    definingClass = conversionContextClassType,
    parameters = listOf(),
    returnType = STRING_BUILDER_TYPE,
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = stringBuilderField
        ),
        opcode(
            opcode = Opcode.RETURN_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)
