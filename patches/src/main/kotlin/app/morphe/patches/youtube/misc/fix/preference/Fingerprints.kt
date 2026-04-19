/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.preference

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FindPreferenceByIndexFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/PreferenceGroup;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I"),
    returnType = "Landroidx/preference/Preference;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;"
        )
    )
)

internal object PreferenceScreenSyntheticFingerprint : Fingerprint(
    name = "run",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        string(":android:show_fragment_args"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Landroidx/preference/PreferenceScreen;"
        ),
        opcode(Opcode.RETURN_VOID),
    )
)

internal object SetPreferenceIconFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/Preference;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/graphics/drawable/Drawable;"),
    returnType = "V"
)

internal object SetPreferenceIconSpaceReservedFingerprint : Fingerprint(
    definingClass = "Landroidx/preference/Preference;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this"
        ),
        opcode(
            opcode = Opcode.IF_EQ,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            parameters = listOf(),
            returnType = "V",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.RETURN_VOID,
            location = MatchAfterImmediately()
        )
    )
)