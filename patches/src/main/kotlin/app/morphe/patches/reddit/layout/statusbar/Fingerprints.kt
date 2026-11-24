/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.statusbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SeeThroughStatusBarPatch;"

internal object HomeScaffoldFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "L",
        "L",
        "Lkotlin/jvm/functions/Function1;",
        "L",
        "Z",
        "Z",
        "Lcom/reddit/listing/model/sort/SortType;",
        "Lkotlin/jvm/functions/Function2;",
        "Z",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "L",
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/runtime/internal/a;",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "I"
    ),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Z", "L"),
            returnType = "F",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "F"),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "J", "L"),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf("L", "F", "F", "F", "F", "F", "L", "Z", "I", "I"),
            returnType = "L",
            location = MatchAfterWithin(40)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object HomeTopBarHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    definingClass = "Lcom/reddit/feedslegacy/switcher/impl/homepager/compose/HomePagerScreen;",
    returnType = "F",
    parameters = listOf(),
    filters = listOf(
        literal(0x42500000),
        opcode(Opcode.RETURN, location = MatchAfterWithin(10))
    )
)

internal object DrawWithContentStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "drawWithContent",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Lkotlin/jvm/functions/Function1;")
)
