package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.util.indexOfFirstLiteralInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches the miniplayer constructor.
 * Identified by the play/pause button resource literal and a unique string in the method body.
 */
internal object MiniPlayerConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "mini_player_play_pause_replay_button")
    ),
    strings = listOf("sharedToggleMenuItemMutations")
)

internal object SwitchToggleColorFingerprint : Fingerprint(
    classFingerprint = MiniPlayerConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "J"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        opcode(Opcode.GOTO, location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET, type = "I"),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
    )
)

internal object MinimizedPlayerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        string("w_st")
    )
)

/**
 * Matches the watch-while layout's onFinishInflate() method.
 * definingClass uses a contains match, covering class renames across builds:
 *   <= 8.x: MppWatchWhileLayout
 *   >= 9.x: WatchWhileLayout
 */
internal object MppWatchWhileLayoutFingerprint : Fingerprint(
    definingClass = "WatchWhileLayout;",
    name = "onFinishInflate",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
)

internal object InteractionLoggingEnumFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("INTERACTION_LOGGING_GESTURE_TYPE_SWIPE")
)

internal object MusicActivityWidgetFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.type.endsWith("/MusicActivity;") &&
                method.indexOfFirstLiteralInstruction(79500L) >= 0
    }
)

internal object HandleSearchRenderedFingerprint : Fingerprint(
    returnType = "V",
    name = "handleSearchRendered"
)

internal object HandleSignInEventFingerprint : Fingerprint(
    classFingerprint = HandleSearchRenderedFingerprint,
    returnType = "V",
    name = "handleSignInEvent",
    filters = listOf(opcode(Opcode.INVOKE_VIRTUAL), opcode(Opcode.RETURN_VOID))
)

internal object MiniPlayerDefaultTextFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "mini_player_default_text")
    )
)

internal object MiniPlayerDefaultViewVisibilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "F"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        opcode(Opcode.SUB_FLOAT_2ADDR),
        opcode(Opcode.SGET_OBJECT),
        opcode(Opcode.INVOKE_VIRTUAL)
    ),
    name = "a",
    custom = { _, classDef -> classDef.methods.count() == 3 }
)