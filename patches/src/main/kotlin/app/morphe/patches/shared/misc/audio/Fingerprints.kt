package app.morphe.patches.shared.misc.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal val formatStreamModelToStringFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    custom = { method, _ ->
        method.name == "toString"
    },
    strings = listOf(
        // Strings are partial matches.
        "isDefaultAudioTrack=",
        "audioTrackId="
    )
)

internal val selectAudioStreamFingerprint = Fingerprint(
    filters = listOf(
        literal(45666189L)
    )
)
