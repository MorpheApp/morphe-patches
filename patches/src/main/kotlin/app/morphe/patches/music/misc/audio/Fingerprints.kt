/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object AllowExclusiveAudioPlaybackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IF_NEZ,
        Opcode.IGET_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT
    )
)

internal object AllowExclusiveAudioPlaybackLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IF_NEZ,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT
    )
)

/** YTM's playback controller for Android Auto, Bluetooth, and other external controls. */
internal object MusicMediaSessionControllerFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("com/google/android/apps/youtube/music/mediabrowser/MusicMediaSessionPlayerController")
    )
)

internal object StopMusicMediaSessionFingerprint : Fingerprint(
    classFingerprint = MusicMediaSessionControllerFingerprint,
    parameters = emptyList(),
    returnType = "L",
    filters = listOf(
        // STOPPAGE_STOP_MUSIC_MEDIA_SESSION.
        literal(33)
    )
)

/** Matches the automatic audio-only restriction after playback loads. */
internal fun externalAudioPlaybackRestrictionFingerprint(stopMethod: MethodReference) = Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, parameters = listOf("L"), returnType = "Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(
            definingClass = "Lj$/util/concurrent/ConcurrentHashMap;",
            name = "keySet",
            parameters = emptyList(),
            returnType = "Ljava/util/Set;",
            location = MatchAfterWithin(7)
        ),
        methodCall(
            definingClass = "Lj$/util/Collection\$-EL;",
            name = "stream",
            parameters = listOf("Ljava/util/Collection;"),
            returnType = "Lj$/util/stream/Stream;",
            location = MatchAfterWithin(2)
        ),
        methodCall(
            definingClass = "Lj$/util/stream/Stream;",
            name = "anyMatch",
            parameters = listOf("Ljava/util/function/Predicate;"),
            returnType = "Z",
            location = MatchAfterWithin(4)
        ),
        // Compare the Next result with "action unavailable" before calling Stop.
        methodCall(
            definingClass = "Lj$/util/Objects;",
            name = "equals",
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Z",
            location = MatchAfterWithin(6)
        ),
        methodCall(reference = stopMethod, location = MatchAfterWithin(3))
    )
)
