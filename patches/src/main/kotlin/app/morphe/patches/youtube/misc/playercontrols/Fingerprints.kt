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
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
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

/**
 * Elevated player, which shows the video metadata in a player overlay.
 */
internal object ElevatedPlayerFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45757309)
    )
)

/**
 * Inflates the video metadata panel with a dark theme, even when the elevated player is off.
 */
internal object MetapanelForceDarkThemeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45813114L) // 21.29+
    )
)

/**
 * Sets a light background on the video metadata panel overlay.
 */
internal object MetapanelForceLightThemeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45840190L) // 21.38+
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
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45752335L)
    )
)

internal object AutonavEndscreenOverlayFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("player_overlay_player_autonav_endscreen")
)

internal object PlayerSeekbarFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45698813)
    )
)

/**
 * Image component of the Elements templates.
 */
internal object ElementsImageComponentFingerprint : Fingerprint(
    strings = listOf("Failed to find a valid source for the image")
)

/**
 * Sets the tint of an image of the Elements templates, with the color given by the image.
 */
internal object ElementsImageTintFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/graphics/drawable/Drawable;", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "I",
            location = MatchFirst()
        ),
        methodCall(
            smali = $$"Landroid/graphics/drawable/Drawable;->setColorFilter(ILandroid/graphics/PorterDuff$Mode;)V"
        )
    )
)
