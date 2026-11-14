package app.morphe.patches.youtube.misc.fix.verticalscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ChoreographerPostFrameCallbackFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45782902L)
    )
)

internal object CanScrollVerticallyFingerprint : Fingerprint(
    definingClass = "Landroidx/swiperefreshlayout/widget/SwipeRefreshLayout;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->canScrollVertically(I)Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * Scrolls the watch page list to the related videos when the video ends.
 * YouTube 21.33+.
 */
internal object ScrollToRelatedItemsOnVideoEndFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        literal(45825801L),
        literal(45825802L),
        string("related-items")
    )
)

/**
 * Handles the "More videos" button of the fullscreen player.
 * Opens the related videos panel only if the panel peeks.
 */
internal object FullscreenMoreVideosButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    filters = listOf(
        // The state of the panel.
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "Z",
            parameters = listOf(),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        // Opens the panel.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "V",
            parameters = listOf("I", "I"),
            location = MatchAfterWithin(4)
        ),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)
