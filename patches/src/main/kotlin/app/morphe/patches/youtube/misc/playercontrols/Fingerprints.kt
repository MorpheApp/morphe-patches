/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.playercontrols

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.layout.player.overlay.CreatePlayerOverviewFingerprint
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PlayerControlsVisibilityEntityModelFingerprint : Fingerprint(
    name = "getPlayerControlsVisibility",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "L",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET,
        Opcode.INVOKE_STATIC
    )
)

internal object ModernPlayerTopControlsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45750838L)
    )
)

internal object PlayerTopControlsInflateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "controls_layout_stub"),
        methodCall(smali = "Landroid/view/ViewStub;->inflate()Landroid/view/View;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

internal object PlayerBottomControlsInflateFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "bottom_ui_container_stub"),
        methodCall(smali = "Landroid/view/ViewStub;->inflate()Landroid/view/View;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

/**
 * Matches same method as [ControlsOverlayFingerprint] and [CreatePlayerOverviewFingerprint].
 */
internal object PlayerBottomGradientScrimFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "bottom_gradient_scrim_overlay"),
        checkCast("Landroid/widget/ImageView;", MatchAfterWithin(10)),
        opcode(Opcode.NEW_INSTANCE),
        opcode(Opcode.IPUT_OBJECT),
        opcode(Opcode.IPUT_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.IPUT_OBJECT, MatchAfterImmediately()),
    )
)

internal object PlayerBottomControlsExploderFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45643739L)
    )
)

internal object PlayerControlsLargeOverlayButtonsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45709810L)
    )
)

internal object PlayerControlsFullscreenLargeButtonsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45686474L) // 21.35 and older.
    )
)

internal object PlayerControlsButtonStrokeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45713296)
    )
)

internal object PlayerControlsModernAccessibilityFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45757309) // Not completely clear what this flag does.
    )
)

internal object PlayerCommentTeaserFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45771730)
    )
)


internal object RecycleViewScrollingFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45763727)
    )
)

internal object NewPlayerOverlaysFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45752335L)
    )
)
