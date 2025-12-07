package app.morphe.patches.youtube.misc.playertype

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.strings
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val playerTypeEnumFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "NONE",
        "HIDDEN",
        "WATCH_WHILE_MINIMIZED",
        "WATCH_WHILE_MAXIMIZED",
        "WATCH_WHILE_FULLSCREEN",
        "WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN",
        "WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED",
        "WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED",
        "INLINE_MINIMAL",
        "VIRTUAL_REALITY_FULLSCREEN",
        "WATCH_WHILE_PICTURE_IN_PICTURE",
    )
)

internal val reelWatchPagerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "reel_watch_player"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(10))
    )
)

internal val videoStateEnumFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    strings = listOf(
        "NEW",
        "PLAYING",
        "PAUSED",
        "RECOVERABLE_ERROR",
        "UNRECOVERABLE_ERROR",
        "ENDED"
    )
)

// 20.33 and lower class name ControlsState. 20.34+ class name is obfuscated.
internal val controlsStateToStringFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("videoState"),
        string("isBuffering")
    )
)
