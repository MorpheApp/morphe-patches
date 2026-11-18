/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val WATCH_WHILE_LAYOUT_CLASS =
    "Lcom/google/android/apps/youtube/music/watchpage/ui/WatchWhileLayout;"

private const val PLAYER_PAGE_BEHAVIOR_CLASS =
    "Lcom/google/android/apps/youtube/music/watchpage/ui/PlayerPageBehavior;"

/**
 * Matches the miniplayer constructor.
 * Identified by the play/pause button resource literal and a unique string in the method body.
 */
internal object MiniPlayerConstructorFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "mini_player_play_pause_replay_button"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById",
            location = MatchAfterWithin(5)
        ),
        string("sharedToggleMenuItemMutations")
    )
)

/**
 * Matches the TabLayout method that assigns the navigation bar background color.
 */
internal object NavigationBarTabLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("FEmusic_radio_builder"),
        resourceLiteral(ResourceType.COLOR, "ytm_color_grey_12"),
        methodCall(name = "setBackgroundColor")
    )
)

internal object SwitchToggleColorParentFingerprint : Fingerprint(
    classFingerprint = MiniPlayerConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "J")
)

internal object SwitchToggleColorFingerprint : Fingerprint(
    classFingerprint = MiniPlayerConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "J"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, smali = "Lj$/time/Duration;->ofMillis(J)Lj$/time/Duration;"),
        opcode(Opcode.IF_NE),
        opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.GOTO, location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "L", location = MatchAfterWithin(5)),
    )
)

internal object SwitchToggleColorLegacyFingerprint : Fingerprint(
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

/**
 * Matches the miniplayer method that animates its colors back to the color in its color field.
 * It compares the color last applied with the color field, and if they differ,
 * saves the color field as the color applied and animates to it for 200 Ms.
 * The second instruction match reads the color field.
 */
internal object MiniplayerResetColorsFingerprint : Fingerprint(
    classFingerprint = MiniPlayerConstructorFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "this",
            type = "I"
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "this",
            type = "I",
            location = MatchAfterImmediately()
        ),
        literal(
            literal = 200L,
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.IF_EQ,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "this",
            type = "I",
            location = MatchAfterImmediately()
        )
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
    definingClass = WATCH_WHILE_LAYOUT_CLASS,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.NEW_ARRAY),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("[Landroid/view/View;"),
            returnType = "V"
        )
    )
)

internal object InteractionLoggingEnumFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("INTERACTION_LOGGING_GESTURE_TYPE_SWIPE"),
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            definingClass = "this"
        )
    )
)

internal object MusicActivityWidgetFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;",
    name = "onCreate",
    filters = listOf(
        string("widget_key"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Ljava/lang/Object;",
            location = MatchAfterWithin(3)
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            location = MatchAfterWithin(3)
        ),
        opcode(
            opcode = Opcode.NEW_INSTANCE,
            location = MatchAfterWithin(3)
        ),
        literal(
            literal = 79500L,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I"),
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("L"),
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L", "L", "L"),
            returnType = "V",
            location = MatchAfterWithin(3)
        ),
    )
)

/**
 * 9.03+
 */
internal object MiniPlayerDefaultTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "mini_player_default_text")
    )
)

/**
 * Matches the method that shows the cold start miniplayer text,
 * by calling [MiniPlayerDefaultTextFingerprint] on the watch-while layout.
 * The first instruction match compares the object passed to the method.
 */
internal object ColdStartMiniplayerTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IF_NE),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = WATCH_WHILE_LAYOUT_CLASS,
            location = MatchAfterImmediately()
        ),
        // The called method is public and final, so it is only called with invoke-virtual.
        methodCall(reference = { MiniPlayerDefaultTextFingerprint.originalMethod })
    )
)

/**
 * 9.02 - 9.42
 */
internal object PlayerPageBehaviorFingerprint : Fingerprint(
    definingClass = PLAYER_PAGE_BEHAVIOR_CLASS,
    accessFlags = listOf(AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = opcodesToFilters(
        Opcode.CONST_4,
        Opcode.IPUT_BOOLEAN,
        Opcode.RETURN_VOID
    )
)

/**
 * 9.43+
 * The first instruction match reads the player page motion field.
 */
internal object PlayerPageBehaviorInterceptTouchEventFingerprint : Fingerprint(
    definingClass = PLAYER_PAGE_BEHAVIOR_CLASS,
    name = "onInterceptTouchEvent",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this"
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Landroid/view/MotionEvent;"),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * 9.43+
 */
internal object PlayerPageBehaviorTouchEventFingerprint : Fingerprint(
    definingClass = PLAYER_PAGE_BEHAVIOR_CLASS,
    name = "onTouchEvent"
)

/**
 * Matches the watch-while layout's onFinishInflate() method.
 * definingClass uses a contains match, covering class renames across builds:
 *   <= 8.x: MppWatchWhileLayout
 *   >= 9.x: WatchWhileLayout
 */
internal object WatchWhileLayoutFingerprint : Fingerprint(
    definingClass = "WatchWhileLayout;",
    returnType = "V",
    filters = listOf(
        // <= 8.x: MppPlayerPageBehavior
        // >= 9.x: PlayerPageBehavior
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "PlayerPageBehavior;"
        ),
        opcode(
            opcode = Opcode.NEW_INSTANCE,
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Matches the method of the warm start miniplayer ([WatchWhileLayoutFingerprint] creates it)
 * that changes the player bottom sheet when the miniplayer text is shown.
 * The last instruction match calls the bottom sheet.
 */
internal object WarmStartMiniplayerTextFingerprint : Fingerprint(
    definingClass = {
        WatchWhileLayoutFingerprint.instructionMatches.last().instruction.getReference<TypeReference>()!!.type
    },
    parameters = { listOf("Landroid/view/View;", "I") },
    filters = listOf(
        fieldAccess(
            opcodes = listOf(Opcode.IGET_OBJECT),
            definingClass = { WatchWhileLayoutFingerprint.originalClassDef.type }
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lcom/google/android/material/bottomsheet/BottomSheetBehavior;",
            parameters = listOf("Z"),
            returnType = "V",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Matches the watch-while layout method that applies a new player state.
 * Every player state change (DISMISSED, MINIMIZED, MAXIMIZED_NOW_PLAYING...) passes through it.
 * The first parameter is the player state enum.
 */
internal object WatchWhileLayoutSetPlayerStateFingerprint : Fingerprint(
    definingClass = WATCH_WHILE_LAYOUT_CLASS,
    returnType = "V",
    parameters = listOf("L", "F")
)

/**
 * Matches the watch-while layout method that requests a new player state,
 * by moving the player bottom sheet to the matching position.
 */
internal object WatchWhileLayoutRequestPlayerStateFingerprint : Fingerprint(
    definingClass = WATCH_WHILE_LAYOUT_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = { listOf(WatchWhileLayoutSetPlayerStateFingerprint.originalMethod.parameterTypes.first()) },
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lcom/google/android/material/bottomsheet/BottomSheetBehavior;",
            parameters = listOf("I"),
            returnType = "V"
        )
    )
)

/**
 * Matches the watch-while dismiss callback (swipe-dismiss or "Dismiss queue"),
 * identified by an IGET_OBJECT of the MusicActivity peer's AtomicBoolean and
 * a following `AtomicBoolean.set(Z)`. The peer class comes from [MusicActivityWidgetFingerprint].
 */
internal object WatchWhileDismissedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcodes = listOf(Opcode.IGET_OBJECT),
            definingClass = { MusicActivityWidgetFingerprint.instructionMatches[1].fieldReference.definingClass },
            type = "Ljava/util/concurrent/atomic/AtomicBoolean;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V",
            location = MatchAfterWithin(3)
        )
    )
)
