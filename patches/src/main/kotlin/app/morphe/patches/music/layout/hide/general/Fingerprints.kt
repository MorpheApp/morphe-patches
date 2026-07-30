/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.general

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode

internal object AudioVideoSwitchPillContainerFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "audio_video_switch_pill_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object InformationButtonFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "information_button"),
        opcode(Opcode.CHECK_CAST)
    )
)

internal object OverlayQueueLoopButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "overlay_queue_loop_button_view"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object OverlayQueueShuffleButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "overlay_queue_shuffle_button_view"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object PlaybackQueueLoopButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "playback_queue_loop_button_view"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object PlaybackQueueShuffleButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "playback_queue_shuffle_button_view"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object QueueLoopButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "queue_loop"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object QueueShuffleButtonFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "queue_shuffle_button"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "findViewById"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)
