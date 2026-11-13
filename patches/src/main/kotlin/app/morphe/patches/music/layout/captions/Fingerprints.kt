/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4773
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private object SubtitleManagerClassFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L", "Landroid/view/accessibility/CaptioningManager;"),
    filters = listOf(
        fieldAccess("Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;"),
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    )
)

/**
 * Sets the caption track of a caption track event.
 *
 * The event has the reason the track is set, which is 2 when the captions are enabled
 * automatically after the caption tracks of a video are loaded.
 * If the track is the option to disable captions, or if there is no track,
 * the default track of the caption tracks is used if there is one.
 */
internal object SetCaptionTrackFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        // Reason the track is set.
        fieldAccess(
            opcode = Opcode.IGET,
            type = "I",
            location = MatchFirst()
        ),
        string(""),
        // If the track is the option to disable captions.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        // If the caption tracks of the video have a default track.
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            location = MatchAfterWithin(10)
        ),
    )
)
