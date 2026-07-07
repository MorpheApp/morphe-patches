package app.morphe.patches.youtube.misc.spoof.appversion

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal object ShortsBoldIconsPrimaryFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45632000L)
    )
)

internal object ShortsBoldIconsSecondaryFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45632045L)
    )
)
