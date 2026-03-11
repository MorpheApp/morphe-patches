package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
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

/**
 * Matches to the class found in [MiniPlayerConstructorFingerprint].
 */
internal object SwitchToggleColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "J"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IGET
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
 *
 * The class was renamed between versions:
 *   <= 8.x: MppWatchWhileLayout
 *   >= 9.x: WatchWhileLayout
 *
 * Both are non-obfuscated, PROTECTED FINAL. Two separate fingerprints are used
 * since definingClass matches a single class name.
 */
internal object MppWatchWhileLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    definingClass = "/MppWatchWhileLayout;",
    name = "onFinishInflate"
)

internal object WatchWhileLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    definingClass = "/WatchWhileLayout;",
    name = "onFinishInflate"
)
