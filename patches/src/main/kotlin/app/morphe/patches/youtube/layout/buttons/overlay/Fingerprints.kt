/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object OverlayCastButtonVisibilityFingerprint : Fingerprint(
    classFingerprint = OverlayCastButtonVisibilityLegacyFingerprint,
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Z"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->setVisibility(I)V"),
        methodCall(smali = "Landroid/view/View;->setEnabled(Z)V")
    )
)

internal object OverlayCastButtonVisibilityLegacyFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            methodCall(
                definingClass = "this",
                parameters = listOf("Landroid/view/View;", "Z")
            ),
            methodCall(name = "setVisibility")
        ),
        literal(11208L)
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

internal object CastButtonPlayerFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45690091)
    )
)

internal object CastButtonActionFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45690090)
    )
)

internal object InflateControlsGroupLayoutStubFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "youtube_controls_button_group_layout_stub"),
        methodCall(name = "inflate")
    )
)

internal object FullscreenButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "fullscreen_button"),
        opcode(Opcode.CHECK_CAST)
    )
)

internal object TitleAnchorFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_collapse_button"),
        opcode(Opcode.CHECK_CAST),

        resourceLiteral(ResourceType.ID, "title_anchor"),
        opcode(Opcode.MOVE_RESULT_OBJECT)
    )
)

internal object SubtitleButtonControllerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lcom/google/android/libraries/youtube/common/ui/TouchImageView;"
        ),
        resourceLiteral(ResourceType.STRING, "accessibility_captions_unavailable"),
        resourceLiteral(ResourceType.STRING, "accessibility_captions_button_name"),
    )
)
