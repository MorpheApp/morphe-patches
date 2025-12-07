package app.morphe.patches.youtube.layout.hide.fullscreenambientmode

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.classDef
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.method
import com.android.tools.smali.dexlib2.AccessFlags

internal object SetFullScreenBackgroundColorFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf("Z", "I", "I", "I", "I"),
    custom = { method, classDef ->
        classDef.type.endsWith("/YouTubePlayerViewNotForReflection;")
                && method.name == "onLayout"
    }
)
