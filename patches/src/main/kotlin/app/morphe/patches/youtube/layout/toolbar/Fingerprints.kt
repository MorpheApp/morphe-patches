package app.morphe.patches.youtube.layout.toolbar

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object ToolBarPatchFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    custom = { method, _ ->
        method.name == "hookToolBar"
    }
)
