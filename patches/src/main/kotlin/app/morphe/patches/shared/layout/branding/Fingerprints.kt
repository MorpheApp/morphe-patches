package app.morphe.patches.shared.layout.branding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object NumberOfPresetAppNamesExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "numberOfPresetAppNames",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf()
)

internal object UserProvidedCustomNameExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "userProvidedCustomName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf()
)

internal object UserProvidedCustomIconExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "userProvidedCustomIcon",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf()
)

// A much simpler fingerprint exists that can set the small icon (contains string "414843287017"),
// but that has limited usage and this fingerprint allows changing any part of the notification.
internal object NotificationBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/app/Notification\$Builder;->setSmallIcon(II)Landroid/app/Notification\$Builder;"
        ),
        string("key_action_priority"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/app/Notification\$Builder;->setColor(I)Landroid/app/Notification\$Builder;"
        ),
    )
)

internal object NotificationIconFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/app/Notification;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT,
            smali = "Landroid/app/Notification;->icon:I",
            location = MatchAfterWithin(3)
        ),
    )
)
