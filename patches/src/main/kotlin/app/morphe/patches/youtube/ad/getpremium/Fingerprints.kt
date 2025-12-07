package app.morphe.patches.youtube.ad.getpremium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val getPremiumViewFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.ADD_INT_2ADDR,
        Opcode.ADD_INT_2ADDR,
        Opcode.INVOKE_VIRTUAL,
        Opcode.RETURN_VOID,
    ),
    custom = { method, _ ->
        method.name == "onMeasure" &&
            method.definingClass == "Lcom/google/android/apps/youtube/app/red/presenter/CompactYpcOfferModuleView;"
    }
)
