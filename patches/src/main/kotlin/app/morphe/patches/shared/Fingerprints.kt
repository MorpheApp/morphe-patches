package app.morphe.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal val castContextFetchFingerprint = Fingerprint(
    filters = listOf(
        string("Error fetching CastContext.")
    )
)

internal val primeMethodFingerprint = Fingerprint(
    filters = listOf(
        string("com.android.vending"),
        string("com.google.android.GoogleCamera")
    )
)

//
// YouTube / YT Music fingerprints
//

// Flag is present in YT 20.23, but bold icons are missing and forcing them crashes the app.
// 20.31 is the first target with all the bold icons present.
internal val boldIconsFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45685201L)
    )
)

