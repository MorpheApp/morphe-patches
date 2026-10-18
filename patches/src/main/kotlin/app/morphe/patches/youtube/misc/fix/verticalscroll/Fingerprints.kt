package app.morphe.patches.youtube.misc.fix.verticalscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
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
