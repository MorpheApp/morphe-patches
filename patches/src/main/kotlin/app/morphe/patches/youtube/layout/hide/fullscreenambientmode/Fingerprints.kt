package app.morphe.patches.youtube.layout.hide.fullscreenambientmode

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object SetFullScreenBackgroundColorFingerprint : Fingerprint(
    definingClass = "/YouTubePlayerViewNotForReflection;",
    name = "onLayout",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf("Z", "I", "I", "I", "I")
)
