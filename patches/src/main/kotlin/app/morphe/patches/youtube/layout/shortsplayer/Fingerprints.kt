package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Purpose of this method is not clear, and it's only used to identify
 * the obfuscated name of the videoId() method in PlaybackStartDescriptor.
 * 20.38 and lower.
 */
internal object PlaybackStartFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(
        "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
            returnType = "Ljava/lang/String;"
        ),
        literal(45380134L)
    )
)

/**
 * Purpose of this method is not entirely clear, and it's only used to identify
 * the obfuscated name of the videoId() method in PlaybackStartDescriptor.
 * 20.39+
 */
internal object WatchPanelVideoIdFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lcom/google/android/apps/youtube/app/common/player/queue/WatchPanelId;"
        ),
        checkCast("Lcom/google/android/apps/youtube/app/common/player/queue/DefaultWatchPanelId;"),
        methodCall(
            definingClass = "Lcom/google/android/apps/youtube/app/common/player/queue/DefaultWatchPanelId;",
            returnType = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;"
        ),
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
            returnType = "Ljava/lang/String;"
        )
    )
)


// 19.25+
internal object ShortsPlaybackIntentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lcom/google/android/libraries/youtube/player/model/PlaybackStartDescriptor;",
        "Ljava/util/Map;",
        "J",
        "Ljava/lang/String;"
    ),
    filters = listOf(
        // None of these strings are unique.
        string("com.google.android.apps.youtube.app.endpoint.flags"),
        string("ReelWatchFragmentArgs"),
        string("reels_fragment_descriptor")
    )
)

internal object ExitVideoPlayerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "mdx_drawer_layout")
    )
)