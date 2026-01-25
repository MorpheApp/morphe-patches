package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object ExperimentFlagUtilFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("Unable to parse proto typed experiment flag: ")
    ),
    custom = { method, _ ->
        // 'public static' or 'public static final'
        AccessFlags.STATIC.isSet(method.accessFlags)
                && AccessFlags.PUBLIC.isSet(method.accessFlags)
                // listOf("L", "J", "[B") or listOf("L", "J")
                && method.parameters.let {
                    (it.size == 2 || it.size == 3) && it[1].type == "J"
                }
    }
)

internal object ExperimentalBooleanFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "J", "Z"),
    custom = { method, _ ->
        // 'public static' or 'public static final'
        AccessFlags.STATIC.isSet(method.accessFlags)
                && AccessFlags.PUBLIC.isSet(method.accessFlags)
    }
)

internal object ExperimentalDoubleFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "D",
    parameters = listOf("J", "D")
)

internal object ExperimentalLongFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf("J", "J")
)

internal object ExperimentalStringFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("J", "Ljava/lang/String;")
)
