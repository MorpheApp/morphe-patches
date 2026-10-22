/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.delay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Media3 renderer getPositionUs:
 * if (state == STATE_STARTED) updateCurrentPosition();
 * return currentPositionUs;
 */
private val positionUsFilters = listOf(
    opcode(Opcode.IGET),
    opcode(Opcode.CONST_4),
    opcode(Opcode.IF_NE),
    opcode(Opcode.INVOKE_DIRECT),
    opcode(Opcode.IGET_WIDE),
    opcode(Opcode.RETURN_WIDE)
)

/**
 * A renderer class that logs with the given tag.
 * The audio sink listener inner classes log with the same tag, but do not extend a renderer.
 */
private fun audioRendererClassFingerprint(logTag: String) = Fingerprint(
    filters = listOf(
        string(logTag)
    ),
    custom = { _, classDef ->
        classDef.superclass != "Ljava/lang/Object;"
    }
)

/**
 * Media3 MediaCodecAudioRenderer getPositionUs, the media clock that video frames are released against
 * when audio is decoded by MediaCodec.
 */
internal object MediaCodecAudioRendererPositionFingerprint : Fingerprint(
    classFingerprint = audioRendererClassFingerprint("MediaCodecAudioRenderer"),
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "J",
    parameters = listOf(),
    filters = positionUsFilters
)

/**
 * Media3 DecoderAudioRenderer getPositionUs, the media clock used when audio is decoded
 * in software, such as Opus audio decoded by LibopusAudioRenderer.
 */
internal object DecoderAudioRendererPositionFingerprint : Fingerprint(
    classFingerprint = audioRendererClassFingerprint("DecoderAudioRenderer"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf(),
    filters = positionUsFilters
)
