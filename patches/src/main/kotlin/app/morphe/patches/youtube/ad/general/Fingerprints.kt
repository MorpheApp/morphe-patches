package app.morphe.patches.youtube.ad.general

import app.morphe.patcher.Fingerprint
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object FullScreenEngagementAdContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ ->
        method.containsLiteralInstruction(fullScreenEngagementAdContainer)
                && indexOfAddListInstruction(method) >= 0
    }
)

internal fun indexOfAddListInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        getReference<MethodReference>()?.name == "add"
    }

internal object LithoDialogBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[B", "L"),
    custom = { method, _ ->
        method.containsLiteralInstruction(slidingDialogAnimation)
                && indexOfShowDialogInstruction(method) >= 0
    }
)

internal fun indexOfShowDialogInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        getReference<MethodReference>()?.name == "show"
    }
