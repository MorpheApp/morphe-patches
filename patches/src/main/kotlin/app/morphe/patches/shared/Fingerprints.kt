/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CastContextFetchFingerprint : Fingerprint(
    filters = listOf(
        string("Error fetching CastContext.")
    )
)

internal object PrimeMethodFingerprint : Fingerprint(
    filters = listOf(
        string("com.android.vending"),
        string("com.google.android.GoogleCamera")
    )
)

//
// YouTube / YT Music fingerprints
//

internal object GoogleApiActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/common/api/GoogleApiActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

// Flag is present in YT 20.23, but bold icons are missing and forcing them crashes the app.
// 20.31 is the first target with all the bold icons present.
internal object BoldIconsFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45685201L)
    )
)

internal object SpannableStringBuilderFingerprint : Fingerprint(
    returnType = "Ljava/lang/CharSequence;",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = SPANNABLE_STRING_REFERENCE
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf(
                "Landroid/text/SpannableString;",
                "Ljava/lang/Object;",
                "I",
                "Z",
                "I"
            )
        ),
        string(
            "Failed to set PB Style Run Extension in TextComponentSpec.",
            comparison = StringComparisonType.STARTS_WITH
        )
    )
)

const val SPANNABLE_STRING_REFERENCE =
    "Landroid/text/SpannableString;->valueOf(Ljava/lang/CharSequence;)Landroid/text/SpannableString;"
