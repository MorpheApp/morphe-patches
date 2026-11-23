/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5201
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patches.reddit.misc.flag.EXPERIMENT_VARIANT_CLASS
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Experiment reader that returns the variant name of an experiment, or null if the user is not
 * in the experiment. The first parameter is the experiment name, followed by booleans.
 *
 * @param booleanParameters Number of boolean parameters after the experiment name.
 */
internal class ExperimentVariantFingerprint(booleanParameters: Int) : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/lang/String;") + List(booleanParameters) { "Z" },
    filters = listOf(
        // Checks the length of the experiment name.
        methodCall(
            definingClass = "Lcom/reddit/experiments/",
            returnType = "V",
            parameters = listOf("Ljava/lang/String;")
        ),
        methodCall(
            smali = "$EXPERIMENT_VARIANT_CLASS->getName()Ljava/lang/String;"
        )
    )
)
