package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
 * 9.02 and lower.
 */
internal object MiniPlayerDefaultTextLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/Object;"),
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IF_NE),
        resourceLiteral(ResourceType.STRING, "mini_player_default_text")
    )
)

/**
 * 9.02+
 */
internal object PlayerPageBehaviorFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/watchpage/ui/PlayerPageBehavior;",
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
 * Matches the watch-while layout's onFinishInflate() method.
 * definingClass uses a contains match, covering class renames across builds:
 *   <= 8.x: MppWatchWhileLayout
 *   >= 9.x: WatchWhileLayout
 */
internal object WatchWhileLayoutFingerprint : Fingerprint(
    definingClass = "WatchWhileLayout;",
    name = "onFinishInflate",
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
 * Matches the watch-while dismiss callback (swipe-dismiss or "Dismiss queue"),
 * identified by an IGET_OBJECT of the MusicActivity peer's AtomicBoolean and
 * a following `AtomicBoolean.set(Z)`. Caller must supply the peer class via
 * [MusicActivityWidgetFingerprint].
 */
internal fun watchWhileDismissedFingerprint(musicActivityPeerClass: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = musicActivityPeerClass,
            type = "Ljava/util/concurrent/atomic/AtomicBoolean;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V",
            location = MatchAfterWithin(3)
        )
    )
)
