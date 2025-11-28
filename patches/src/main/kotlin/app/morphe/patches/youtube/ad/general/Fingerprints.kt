package app.morphe.patches.youtube.ad.general

import app.revanced.patcher.fingerprint
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val fullScreenEngagementAdContainerFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters()
    custom { method, _ ->
        method.containsLiteralInstruction(fullScreenEngagementAdContainer)
                && indexOfAddListInstruction(method) >= 0
    }
}

internal fun indexOfAddListInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        getReference<MethodReference>()?.name == "add"
    }

