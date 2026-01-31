@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val MINIPLAYER_MODERN_FEATURE_KEY = 45622882L
// In later targets this feature flag does nothing and is dead code.
internal const val MINIPLAYER_MODERN_FEATURE_LEGACY_KEY = 45630429L
internal const val MINIPLAYER_DOUBLE_TAP_FEATURE_KEY = 45628823L
internal const val MINIPLAYER_DRAG_DROP_FEATURE_KEY = 45628752L
internal const val MINIPLAYER_HORIZONTAL_DRAG_FEATURE_KEY = 45658112L
internal const val MINIPLAYER_ROUNDED_CORNERS_FEATURE_KEY = 45652224L
internal const val MINIPLAYER_INITIAL_SIZE_FEATURE_KEY = 45640023L
internal const val MINIPLAYER_DISABLED_FEATURE_KEY = 45657015L
internal const val MINIPLAYER_ANIMATED_EXPAND_FEATURE_KEY = 45644360L

internal object MiniplayerModernConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        literal(45623000L) // Magic number found in the constructor.
    )
)

internal object MiniplayerDimensionsCalculatorParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "floaty_bar_button_top_margin")
    )
)

internal object MiniplayerModernViewParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("player_overlay_modern_mini_player_controls")
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernAddViewListenerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernCloseButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "modern_miniplayer_close"),
        checkCast("Landroid/widget/ImageView;")
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernExpandButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "modern_miniplayer_expand"),
        checkCast("Landroid/widget/ImageView;")
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernExpandCloseDrawablesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(ytOutlinePictureInPictureWhite24)
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernForwardButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "modern_miniplayer_forward_button"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterWithin(5))
    )
)

internal object MiniplayerModernOverlayViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "scrim_overlay"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterWithin(5))
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernRewindButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "modern_miniplayer_rewind_button"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterWithin(5))
    )
)

/**
 * Matches using the class found in [miniplayerModernViewParentFingerprint].
 */
internal object MiniplayerModernActionButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "modern_miniplayer_overlay_action_button"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterWithin(5))
    )
)

internal object MiniplayerMinimumSizeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "miniplayer_max_size"),
        anyInstruction( // Default miniplayer width constant.
            literal(192),
            literal(192.0f), // 21.03+
        ),
        anyInstruction( // Default miniplayer height constant.
            literal(128),
            literal(128.0f), // 21.03+
        )
    )
)

internal object MiniplayerOverrideFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    filters = listOf(
        string("appName"),
        methodCall(
            parameters = listOf("Landroid/content/Context;"),
            returnType = "Z",
            location = MatchAfterWithin(10)
        )
    )
)

internal object MiniplayerOverrideNoContextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN) // Anchor to insert the instruction.
    )
)

/**
 * 20.36 and lower. Codes appears to be removed in 20.37+
 */
internal object MiniplayerResponseModelSizeCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.RETURN_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.CHECK_CAST,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.IF_NEZ,
    )
)

internal object MiniplayerOnCloseHandlerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(MINIPLAYER_DISABLED_FEATURE_KEY)
    )
)

internal const val YOUTUBE_PLAYER_OVERLAYS_LAYOUT_CLASS_NAME =
    "Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;"

internal object PlayerOverlaysLayoutFingerprint : Fingerprint(
    definingClass = YOUTUBE_PLAYER_OVERLAYS_LAYOUT_CLASS_NAME
)

internal object MiniplayerSetIconsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/Runnable;"),
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "yt_fill_pause_white_36"),
        resourceLiteral(ResourceType.DRAWABLE, "yt_fill_pause_black_36")
    )
)
