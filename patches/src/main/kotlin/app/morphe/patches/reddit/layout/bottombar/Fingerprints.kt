/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5460
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.bottombar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Color of a selected and of an unselected navigation button, chosen with the selected state.
 */
private val buttonColorFilters = arrayOf(
    // Selected color.
    methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(), returnType = "J"),
    opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately()),
    // Unselected color.
    methodCall(
        opcode = Opcode.INVOKE_VIRTUAL,
        parameters = listOf(),
        returnType = "J",
        location = MatchAfterWithin(8)
    ),
    opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately())
)

/**
 * Button of the bottom navigation bar and of the side rail. Some app versions have two of these
 * methods, chosen by a server flag. Each chooses the icon color and then creates
 * the composable lambda that draws the label.
 */
internal object BottomNavButtonColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("bottom_nav_button"),
        *buttonColorFilters,
        // Label lambda.
        methodCall(opcode = Opcode.INVOKE_DIRECT, name = "<init>"),
        // Compose group key.
        opcode(Opcode.CONST, location = MatchAfterImmediately()),
        // rememberComposableLambda()
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Landroidx/compose/runtime/internal/a;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Label lambda of a navigation button, that chooses the label color.
 * It can be a lambda class shared with other composables.
 */
internal class BottomNavButtonLabelFingerprint(labelClass: String) : Fingerprint(
    definingClass = labelClass,
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(*buttonColorFilters)
)
