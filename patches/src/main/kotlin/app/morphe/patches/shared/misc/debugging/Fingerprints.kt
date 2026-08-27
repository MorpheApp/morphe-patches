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
                ))
    }
)
