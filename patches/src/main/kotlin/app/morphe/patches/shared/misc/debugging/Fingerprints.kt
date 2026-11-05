/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal object ExperimentFlagUtilFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        string("Unable to parse proto typed experiment flag: ")
    ),
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                (parametersMatch(
                    method.parameters,
                    listOf("L", "J", "[B")
                ) || parametersMatch(
                    method.parameters,
                    listOf("L", "J")
                ) || parametersMatch( // 21.35+
                    method.parameters,
                    listOf("J", "[B")
                ) || parametersMatch( // 21.42+
                    method.parameters,
                    listOf("I", "J", "[B")
                ))
    }
)

internal object ExperimentalBooleanFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "Z",
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                (parametersMatch(
                    method.parameters,
                    listOf("L", "J", "Z")
                ) || parametersMatch( // 21.35+
                    method.parameters,
                    listOf("J", "Z")
                ))
    }
)

/**
 * 21.42+ boolean accessor with an additional int parameter.
 * The [ExperimentalBooleanFeatureFlagFingerprint] accessor still exists and is also used.
 */
internal object ExperimentalIndexedBooleanFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "Z",
    parameters = listOf("I", "J", "Z"),
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags)
    }
)

internal object ExperimentalDoubleFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "D",
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                (parametersMatch(
                    method.parameters,
                    listOf("L", "J", "D")
                ) || parametersMatch( // 21.35+
                    method.parameters,
                    listOf("J", "D")
                ) || parametersMatch( // 21.42+
                    method.parameters,
                    listOf("I", "J", "D")
                ))
    }
)

internal object ExperimentalLongFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "J",
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                (parametersMatch(
                    method.parameters,
                    listOf("L", "J", "J"),
                ) || parametersMatch( // 21.35+
                    method.parameters,
                    listOf("J", "J"),
                ) || parametersMatch( // 21.42+
                    method.parameters,
                    listOf("I", "J", "J"),
                ))
    }
)

internal object ExperimentalStringFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "Ljava/lang/String;",
    custom = { method, _ ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                (parametersMatch(
                    method.parameters,
                    listOf("L", "J", "Ljava/lang/String;")
                ) || parametersMatch( // 21.35+
                    method.parameters,
                    listOf("J", "Ljava/lang/String;")
                ) || parametersMatch( // 21.42+
                    method.parameters,
                    listOf("I", "J", "Ljava/lang/String;")
                ))
    }
)

private fun isHotFeatureFlagAccessor(method: Method) =
    AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            (parametersMatch(
                method.parameters,
                listOf("J")
            ) || parametersMatch( // YouTube 21.42+, YT Music 9.42+
                method.parameters,
                listOf("I", "J")
            ))

/**
 * Hot config accessors without a default value parameter. YouTube 21.35+ and YT Music 9.37+.
 * Older versions read hot flags with the same static methods as the other accessors.
 */
internal object ExperimentalHotBooleanFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "Z",
    custom = { method, _ -> isHotFeatureFlagAccessor(method) }
)

internal object ExperimentalHotDoubleFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "D",
    custom = { method, _ -> isHotFeatureFlagAccessor(method) }
)

internal object ExperimentalHotLongFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "J",
    custom = { method, _ -> isHotFeatureFlagAccessor(method) }
)

/**
 * YouTube only. With YT Music the hot and cold string accessors have the same parameters.
 */
internal object ExperimentalHotStringFeatureFlagFingerprint : Fingerprint(
    classFingerprint = ExperimentFlagUtilFingerprint,
    returnType = "Ljava/lang/String;",
    custom = { method, _ -> isHotFeatureFlagAccessor(method) }
)

/**
 * Bootstrap store. Bit packed flags saved to disk and read on startup,
 * before the server config is loaded. Server flags are copied to it when a config is applied.
 */
internal object BootstrapStoreSerializeFingerprint : Fingerprint(
    filters = listOf(
        string("!serialize")
    )
)

/**
 * All bootstrap store reads use this method. The parameter is the packed flag id.
 */
internal object BootstrapStoreReadFingerprint : Fingerprint(
    classFingerprint = BootstrapStoreSerializeFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf("I")
)
