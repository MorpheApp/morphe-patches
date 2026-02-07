package app.morphe.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
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

internal object ProtobufClassParseByteArrayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "[B"),
    custom = { method, _ -> method.name == "parseFrom" }
)

//
// On YouTube, this class is:
// Lcom/google/android/libraries/youtube/innertube/model/media/FormatStreamModel;
// On YouTube Music, class names are obfuscated.
//
internal object FormatStreamModelConstructorFingerprint : Fingerprint(
    accessFlags = listOf(
        AccessFlags.PUBLIC,
        AccessFlags.CONSTRUCTOR
    ),
    returnType = "V",
    filters = buildList {
        addAll(
            OpcodesFilter.opcodesToFilters(
                Opcode.IGET_WIDE,
                Opcode.IPUT_WIDE
            )
        )
        add(literal(45374643L))
    }
)