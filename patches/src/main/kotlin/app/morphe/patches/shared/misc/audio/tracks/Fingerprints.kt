package app.morphe.patches.shared.misc.audio.tracks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal object FormatStreamModelToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        // Strings are partial matches.
        "isDefaultAudioTrack=",
        "audioTrackId="
    )
)

internal object SelectAudioStreamFingerprint : Fingerprint(
    filters = listOf(
        // YT 21.25 and older.
        // 21.26+ usage appears to be partially replaced with flag 45673827L
        literal(45666189L)
    )
)
