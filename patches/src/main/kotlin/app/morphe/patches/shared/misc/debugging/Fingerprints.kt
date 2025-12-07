package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val experimentalFeatureFlagParentFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "J", "[B"),
    strings = listOf("Unable to parse proto typed experiment flag: ")
)

internal val experimentalBooleanFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L", "J", "Z")
)

internal val experimentalDoubleFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "D",
    parameters = listOf("J", "D")
)

internal val experimentalLongFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf("J", "J")
)

internal val experimentalStringFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("J", "Ljava/lang/String;")
)
