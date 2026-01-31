package app.morphe.patches.youtube.layout.toolbar

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object ToolBarPatchFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "hookToolBar",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC)
)
