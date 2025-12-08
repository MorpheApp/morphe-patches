package app.morphe.patches.youtube.misc.loopvideo

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object VideoStartPlaybackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("play() called when the player wasn't loaded."),
        string("play() blocked because Background Playability failed")
    )
)
