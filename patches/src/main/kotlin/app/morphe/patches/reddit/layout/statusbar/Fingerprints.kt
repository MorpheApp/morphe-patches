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
import com.android.tools.smali.dexlib2.Opcode

/**
 * Home feed and the app bar above it.
 */
internal object HomeScaffoldFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        // If the side rail is shown.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        // App bar text height.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Z", "L"),
            returnType = "F",
            location = MatchAfterWithin(5)
        ),

        // Modifier of the feed.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Landroidx/compose/ui/input/nestedscroll/c;"
        ),
        // WindowInsets.statusBars
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterWithin(20)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Modifier.windowInsetsPadding
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L"),
            returnType = "L",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),

        // Modifier.background of the app bar.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "J", "L"),
            returnType = "L"
        ),
        // Modifier.graphicsLayer, with the alpha of the app bar.
        methodCall(
            definingClass = "Landroidx/compose/ui/graphics/d;",
            returnType = "L",
            location = MatchAfterWithin(50)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object HomeTopBarHeightFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feedslegacy/switcher/impl/homepager/compose/HomePagerScreen;",
    returnType = "F",
    parameters = listOf(),
    filters = listOf(
        literal(52f),
        opcode(Opcode.RETURN, location = MatchAfterWithin(10))
    )
)
