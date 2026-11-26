/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val PLAYBACK_AGNOSTIC_USER_PREFERENCES =
    "Lcom/google/android/libraries/youtube/media/interfaces/PlaybackAgnosticUserPreferences;"

/**
 * Code that creates the playback preferences of the media requests:
 * `preferences.isDrcEnabled()` -> `preferences.getAudioTrackId()` ->
 * `new PlaybackAgnosticUserPreferences(isDrcEnabled, audioTrackId, ...)`, directly or with a factory.
 *
 * The first match is the method that gets if DRC audio is enabled. It is called with
 * invoke-virtual, while other code creates the preferences from an interface with a copy of it.
 * More than one method creates the preferences, and all call the same method.
 */
internal object PlaybackAgnosticUserPreferencesFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        methodCall(
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            location = MatchAfterWithin(3)
        ),
        anyInstruction(
            // 20.26.
            newInstance(PLAYBACK_AGNOSTIC_USER_PREFERENCES),
            // 20.51+
            methodCall(returnType = PLAYBACK_AGNOSTIC_USER_PREFERENCES),
            location = MatchAfterWithin(15)
        )
    )
)

internal object VolumeNormalizationConfigLegacyFingerprint : Fingerprint (
    filters = listOf(
        opcode(opcode = Opcode.IGET_OBJECT),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IF_NEZ, location = MatchAfterWithin(5)),
        opcode(opcode = Opcode.IGET_OBJECT),
        string("rng."),
        string(";trkcfg.")
    )
)

internal object VolumeNormalizationConfigFingerprint : Fingerprint (
    filters = listOf(
        string("rng."),
        string(";trkcfg.")
    ),
    custom = { method, _ ->
        method.parameters.size >= 3
    }
)
