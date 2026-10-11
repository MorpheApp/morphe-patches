/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.silence

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * DefaultAudioSink's applyAudioProcessorPlaybackParameters,
 * where the silence skipping processor flag is set.
 */
internal object ApplySkipSilenceFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        filters = listOf(
            string("AudioTrackAudioOutput"),
            string("Failed to set playback params")
        ),
    ),
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "L"
        ),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(2)
        ),
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IPUT_BOOLEAN
        )
    )
)

/**
 * DefaultAudioSink's setSkipSilenceEnabled.
 */
internal object SetSkipSilenceEnabledFingerprint : Fingerprint(
    classFingerprint = ApplySkipSilenceFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z")
)

/**
 * Constructor of the silence skipping audio processor:
 * minimum silence duration (us), retention ratio, max silence to keep (us), min volume percentage, threshold level.
 */
internal object SilenceSkippingProcessorConstructorFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        filters = listOf(
            string("bytesConsumed is not aligned to frame size: %s")
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("J", "F", "J", "I", "S"),
)

/**
 * Silence skipping processor's queueInput, which receives the decoded audio.
 */
internal object SilenceSkippingProcessorQueueInputFingerprint : Fingerprint(
    classFingerprint = SilenceSkippingProcessorConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/nio/ByteBuffer;")
)

/**
 * Silence skipping processor's isNoise, which compares an audio sample with the silence threshold level.
 */
internal object SilenceSkippingProcessorIsNoiseFingerprint : Fingerprint(
    classFingerprint = SilenceSkippingProcessorConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("B", "B"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_SHORT,
            definingClass = "this"
        )
    )
)

/**
 * Silence skipping processor's onFlush, the only method without parameters that allocates the silence buffers.
 */
internal object SilenceSkippingProcessorFlushFingerprint : Fingerprint(
    classFingerprint = SilenceSkippingProcessorConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.NEW_ARRAY)
    )
)
