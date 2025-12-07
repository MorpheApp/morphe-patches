package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val numberOfPresetAppNamesExtensionFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "numberOfPresetAppNames" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

// A much simpler fingerprint exists that can set the small icon (contains string "414843287017"),
// but that has limited usage and this fingerprint allows changing any part of the notification.
internal val notificationFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L"),
    strings = listOf("key_action_priority")
)
