package app.morphe.patches.shared.misc.drc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CompressionRatioFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lj$/util/Optional;",
    parameters = emptyList(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IF_EQZ,
        Opcode.IGET,
        Opcode.NEG_FLOAT
    )
)

internal const val VOLUME_NORMALIZATION_EXPERIMENTAL_FEATURE_FLAG = 45425391L

internal object VolumeNormalizationConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(VOLUME_NORMALIZATION_EXPERIMENTAL_FEATURE_FLAG)
    )
)