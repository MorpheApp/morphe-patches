package app.morphe.patches.music.layout.buttons

import app.revanced.patcher.fingerprint
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.literal
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val mediaRouteButtonFingerprint = fingerprint {
    accessFlags(AccessFlags.PRIVATE, AccessFlags.FINAL)
    returns("Z")
    strings("MediaRouteButton")
}

internal val playerOverlayChipFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("L")
    literal { playerOverlayChip }
}

internal val historyMenuItemFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters("Landroid/view/Menu;")
    opcodes(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    )
    custom { method, classDef ->
        method.containsLiteralInstruction(historyMenuItem) &&
            classDef.methods.count() == 5
    }
}

internal val historyMenuItemOfflineTabFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("V")
    parameters("Landroid/view/Menu;")
    opcodes(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    )
    custom { method, _ ->
        method.containsLiteralInstruction(historyMenuItem) &&
            method.containsLiteralInstruction(offlineSettingsMenuItem)
    }
}

internal val searchActionViewFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("Landroid/view/View;")
    parameters()
    custom { method, classDef ->
        method.containsLiteralInstruction(searchButton) &&
            classDef.type.endsWith("/SearchActionProvider;")
    }
}

internal val topBarMenuItemImageViewFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returns("Landroid/view/View;")
    parameters()
    literal { topBarMenuItemImageView }
}
