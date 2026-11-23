/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.flag

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal const val EXPERIMENT_VARIANT_CLASS = "Lcom/reddit/common/experiments/ExperimentVariant;"

/**
 * Feature flag reader that returns if an experiment is enabled.
 * The first parameter is the experiment name.
 *
 * 2026.47.0+
 */
internal object FeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;", "Z", "Z", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/reddit/experiments/",
            returnType = EXPERIMENT_VARIANT_CLASS
        ),
        methodCall(
            smali = "$EXPERIMENT_VARIANT_CLASS->getName()Ljava/lang/String;"
        )
    )
)

internal object FeatureFlagLegacyFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        returnType = "V",
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        parameters = listOf("Ljava/lang/String;"),
        filters = listOf(
            string("experiment_name"),
            string("max_length")
        )
    ),
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;", "Z")
)
