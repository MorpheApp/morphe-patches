/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 * https://github.com/MorpheApp/morphe-patches/pull/3451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.icons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Sets the icon of the play, pause and replay button for a new player state.
 */
internal object PlayPauseButtonStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "accessibility_play"),
        resourceLiteral(ResourceType.STRING, "accessibility_pause"),
        resourceLiteral(ResourceType.STRING, "accessibility_replay"),
        resourceLiteral(ResourceType.DRAWABLE, "player_replay"),
        methodCall(definingClass = "Landroid/widget/ImageView;", name = "setImageDrawable"),
    )
)

/**
 * In the class that sets the player overlay icons from code.
 */
internal object PlayerOverlayControlsFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_control_previous_button"),
        resourceLiteral(ResourceType.ID, "player_control_next_button"),
    )
)

/**
 * Loads and tints an icon of the player controls.
 */
internal object PlayerControlIconLoaderFingerprint : Fingerprint(
    returnType = "Landroid/graphics/drawable/Drawable;",
    parameters = listOf(
        "Lcom/google/android/libraries/youtube/common/ui/TouchImageView;",
        "I",
        "Landroid/graphics/drawable/Drawable;",
        "I",
    ),
    filters = listOf(
        methodCall(definingClass = "Landroid/content/res/Resources;", name = "getDrawable"),
    )
)

/**
 * In the class that sets the icon of the captions button.
 */
internal object PlayerCaptionsButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "quantum_ic_closed_caption_white_24"),
        resourceLiteral(ResourceType.DRAWABLE, "quantum_ic_closed_caption_off_white_24"),
    )
)
