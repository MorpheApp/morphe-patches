/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.originaltitles

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Binds a video of the playlist panel (the queue of a playlist or mix on the watch page).
 * The panel has multiple layouts, each with its own bind method.
 */
internal object PlaylistPanelVideoBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        // Title view.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/widget/TextView;"),
        string("PLAYLIST_CURRENT_VIDEO_MONITOR"),
        // Video id.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(10)
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Binds the video information shown on the watch page while the video is loading,
 * which shows the title of the element that opened the video.
 */
internal object LoadingVideoInformationBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "channel_navigation_container"),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        resourceLiteral(ResourceType.ID, "title", location = MatchAfterWithin(8)),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        checkCast("Landroid/widget/TextView;", location = MatchAfterWithin(2)),
        resourceLiteral(ResourceType.ID, "collapsed_subtitle", location = MatchAfterWithin(8))
    )
)

/**
 * Creates the title view of the next video of a playlist, shown when the playlist panel is collapsed.
 */
internal object NextVideoTitleViewFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "next_video_title"),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        checkCast("Landroid/widget/TextView;", location = MatchAfterWithin(2)),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/TextView;",
            location = MatchAfterImmediately()
        )
    )
)
