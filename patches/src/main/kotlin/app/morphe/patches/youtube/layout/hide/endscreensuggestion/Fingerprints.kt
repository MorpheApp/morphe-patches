package app.morphe.patches.youtube.layout.hide.endscreensuggestion

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val autoNavConstructorFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    strings = listOf("main_app_autonav")
)

internal val autoNavStatusFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf()
)

internal val removeOnLayoutChangeListenerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IPUT,
        Opcode.INVOKE_VIRTUAL
    ),
    // This is the only reference present in the entire smali.,
    custom = { method, _ ->
        method.indexOfFirstInstruction {
            val reference = getReference<MethodReference>()
            reference?.name == "removeOnLayoutChangeListener" &&
            reference.definingClass.endsWith("/YouTubePlayerOverlaysLayout;")
        } >= 0
    }
)