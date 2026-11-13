/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1842
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.sponsorblock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches {@code MusicPlaybackControlsTimeBar.draw(Canvas)}.
 * Draws segment markers on the compact/mini player seekbar.
 */
internal object MusicTimeBarDrawFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;",
    name = "draw",
    returnType = "V"
)

/**
 * Matches {@code MusicPlaybackControlsTimeBar.onMeasure(int, int)}.
 * Resolves the Rect field used for seekbar bounds.
 */
internal object MusicTimeBarOnMeasureFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;",
    name = "onMeasure",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Landroid/graphics/Rect;",
            location = MatchAfterLast()
        )
    )
)

private const val REMOVE_OFFLINE_TRACK_STRING = "Couldn't delete single track through orchestration: %s"

/**
 * Matches {@code MusicTrackDownloadController.removeSingleTrack(String, String)}.
 * Removes a saved song or video, after any prompt is confirmed.
 */
internal object MusicRemoveOfflineTrackFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;", // Video ID
        "Ljava/lang/String;"
    ),
    filters = listOf(
        string(REMOVE_OFFLINE_TRACK_STRING)
    )
)

/**
 * Removes a saved song or video with its own copy of the code of [MusicRemoveOfflineTrackFingerprint].
 * Returns early without removing it if the app cannot remove it right now.
 */
internal object MusicRemoveOfflineTrackActionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"), // Video ID
    filters = listOf(
        opcode(Opcode.RETURN_VOID), // Early return.
        string(REMOVE_OFFLINE_TRACK_STRING)
    )
)
