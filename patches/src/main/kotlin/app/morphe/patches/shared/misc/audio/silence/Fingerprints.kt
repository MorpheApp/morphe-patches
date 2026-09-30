/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.silence

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * DefaultAudioSink's applyAudioProcessorPlaybackParameters, where the silence skipping processor flag is set.
 */
internal object ApplySkipSilenceFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.IGET_BOOLEAN, location = MatchAfterImmediately()),
        opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        opcode(Opcode.IPUT_BOOLEAN, location = MatchAfterImmediately()),
    ),
)

/**
 * DefaultAudioSink's setSkipSilenceEnabled.
 */
internal object SetSkipSilenceEnabledFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
)

/**
 * Constructor of the silence skipping audio processor:
 * minimum silence duration (us), retention ratio, max silence to keep (us), min volume percentage, threshold level.
 */
internal object SilenceSkippingProcessorConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("J", "F", "J", "I", "S"),
)
