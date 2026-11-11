/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.loop

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/** Fingerprint: MusicPlaybackControlsTimeBar.draw(Canvas). */
internal object LoopMusicTimeBarDrawFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;",
    name = "draw",
    returnType = "V",
)

/** Fingerprint: MusicPlaybackControlsTimeBar.onMeasure(int, int). */
internal object LoopMusicTimeBarOnMeasureFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;",
    name = "onMeasure",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Landroid/graphics/Rect;",
            location = MatchAfterLast(),
        ),
    ),
)

/** Fingerprint: MediaLib player seek (in-app + media-session). */
internal object LoopMediaPlayerSeekFingerprint : Fingerprint(
    parameters = listOf("J", "L"),
    returnType = "V",
    filters = listOf(
        string("MedialibPlayer.seekTo(positionMillis=%d)"),
    ),
)

/** Fingerprint: MusicPlaybackControlsTimeBar.c(float); thumb-position setter. */
internal object LoopMusicTimeBarSeekFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;",
    parameters = listOf("F"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Landroid/graphics/Rect;",
        ),
        methodCall(
            definingClass = "Ljava/lang/Math;",
            name = "max",
        ),
    ),
)


