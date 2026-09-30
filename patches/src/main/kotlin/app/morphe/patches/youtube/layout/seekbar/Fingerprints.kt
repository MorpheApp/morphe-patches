/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PlayerSeekbarColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_played_not_highlighted_color"),
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_colorized_bar_played_color_dark")
    )
)

// class is ControlsOverlayStyle in 20.32 and lower, and obfuscated in 20.33+
internal object SetSeekbarClickedColorFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.CONST_HIGH16),
        methodCall()
    ),
    strings = listOf("YOUTUBE", "PREROLL", "POSTROLL", "REMOTE_LIVE", "AD_LARGE_CONTROLS")
)

internal object ShortsSeekbarColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "reel_time_bar_played_color")
    )
)

internal object PlayerSeekbarHandle1ColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_live_seekable_range"),
        resourceLiteral(ResourceType.ATTR, "ytStaticBrandRed"),
    )
)

internal object PlayerSeekbarHandle2ColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.ATTR, "ytTextSecondary"),
        resourceLiteral(ResourceType.ATTR, "ytStaticBrandRed"),
    )
)

internal object WatchHistoryMenuUseProgressDrawableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(definingClass = "Landroid/widget/ProgressBar;", name = "setMax"),
        opcode(Opcode.MOVE_RESULT),
        literal(-1712394514)
    )
)

internal object LithoLinearGradientFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Landroid/graphics/LinearGradient;",
    parameters = listOf("F", "F", "F", "F", "[I", "[F"),
)

/**
 * 19.49+
 */
internal object PlayerLinearGradientFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("I", "I", "I", "I", "Landroid/content/Context;", "I"),
    returnType = "Landroid/graphics/LinearGradient;",
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "yt_youtube_magenta"),

        opcode(Opcode.FILLED_NEW_ARRAY, location = InstructionLocation.MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object LottieSplashScreenFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            literal(268507948L), // 20.21.37
            literal(1073814316L)
        )
    )
)

internal object ShortsWhiteSeekbarFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45787913)
    )
)
