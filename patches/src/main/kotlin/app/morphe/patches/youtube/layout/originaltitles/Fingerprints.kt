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
import app.morphe.patcher.opcode
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
 * Binds a compact video, such as a video of the watch history.
 */
internal object CompactVideoBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        // Renderer of the video.
        opcode(Opcode.CHECK_CAST),
        resourceLiteral(ResourceType.INTEGER, "compact_renderer_title_max_lines"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "[B"),
        methodCall(returnType = "Landroid/text/Spanned;"),
        // Sets the title.
        methodCall(
            parameters = listOf("Ljava/lang/CharSequence;"),
            returnType = "V",
            location = MatchAfterWithin(2)
        )
    )
)

/**
 * Sets the title of a compact video.
 */
internal fun compactVideoTitleSetterFingerprint(definingClass: String, name: String) = Fingerprint(
    definingClass = definingClass,
    name = name,
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/widget/TextView;"),
        methodCall(name = "setText", location = MatchAfterWithin(2))
    )
)

/**
 * Binds the suggested video shown at the end of a video in fullscreen.
 */
internal object SuggestedVideoEndScreenBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/view/View;"),
    filters = listOf(
        // Renderer of the suggested video.
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(2)),
        // Sets the title.
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/common/ui/YouTubeTextView;",
            name = "setText",
            location = MatchAfterWithin(10)
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
 * Creates the fullscreen engagement overlay, shown by swiping up in fullscreen.
 */
internal object FullscreenEngagementOverlayFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "fullscreen_engagement_overlay"),
        resourceLiteral(ResourceType.ID, "engagement_title"),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        checkCast("Landroid/widget/TextView;", location = MatchAfterWithin(2)),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/TextView;",
            location = MatchAfterImmediately()
        )
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

/**
 * Creates the title view of the minimal miniplayer.
 */
internal object MinimalMiniplayerTitleViewFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "floaty_title"),
        methodCall(name = "findViewById", location = MatchAfterImmediately()),
        checkCast("Landroid/widget/TextView;", location = MatchAfterWithin(2)),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/TextView;",
            location = MatchAfterImmediately()
        )
    )
)
