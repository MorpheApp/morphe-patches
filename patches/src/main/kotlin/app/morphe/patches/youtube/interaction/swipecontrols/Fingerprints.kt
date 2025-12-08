package app.morphe.patches.youtube.interaction.swipecontrols

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import com.android.tools.smali.dexlib2.AccessFlags

internal object SwipeControlsHostActivityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    custom = { method, _ ->
        method.definingClass == EXTENSION_CLASS_DESCRIPTOR
    }
)

internal object SwipeChangeVideoFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        literal(45631116L) // Swipe to change fullscreen video feature flag.
    )
)
