package app.morphe.patches.youtube.video.codecs

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.strings
import com.android.tools.smali.dexlib2.AccessFlags

internal object Vp9CapabilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    strings = listOf(
        "vp9_supported",
        "video/x-vnd.on2.vp9"
    )
)
