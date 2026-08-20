/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import com.android.tools.smali.dexlib2.Opcode

internal object UseGradientLoadingScreenFingerprint : Fingerprint(
    filters = listOf(
        literal(45412406L)
    )
)

internal object CarbonColorThemeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45760313)
    )
)

internal object SplashScreenStyleFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        anyInstruction(
            literal(1074339245), // 20.30+
            literal(269032877L) // 20.29 and lower.
        )
    )
)

/**
 * Matches to the same method as [SplashScreenStyleFingerprint].
 */
internal object ShowSplashScreenFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Z",
            parameters = listOf("I")
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.IF_EQZ,
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.GOTO,
            location = MatchAfterWithin(2)
        ),
        anyInstruction(
            opcode(Opcode.CONST_4),
            opcode(Opcode.CONST_16),
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.IF_NE,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The pivot bar creates the view stub of the new content dot, and of the count next to it.
 * The count is matched as well because the effects picker uses the same dot id.
 */
internal object PivotBarNewContentDotFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "new_content_dot"),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        resourceLiteral(
            ResourceType.ID,
            "new_content_count",
            location = MatchAfterWithin(30)
        ),
        opcode(opcode = Opcode.CHECK_CAST)
    )
)
