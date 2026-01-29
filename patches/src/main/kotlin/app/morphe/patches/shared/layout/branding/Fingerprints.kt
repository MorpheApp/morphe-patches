package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object NumberOfPresetAppNamesExtensionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(),
    custom = { method, _ ->
        method.name == "numberOfPresetAppNames"
    }
)

internal object UserProvidedCustomNameExtensionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    custom = { method, _ ->
        method.name == "userProvidedCustomName"
    }
)

internal object UserProvidedCustomIconExtensionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    custom = { method, _ ->
        method.name == "userProvidedCustomIcon"
    }
)

// A much simpler fingerprint exists that can set the small icon (contains string "414843287017"),
// but that has limited usage and this fingerprint allows changing any part of the notification.
internal object NotificationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L"),
    strings = listOf("key_action_priority")
)
