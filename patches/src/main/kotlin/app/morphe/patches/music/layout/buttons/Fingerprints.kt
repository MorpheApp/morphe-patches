package app.morphe.patches.music.layout.buttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val mediaRouteButtonFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    strings = listOf("MediaRouteButton")
)

internal val playerOverlayChipFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    custom = customLiteral { playerOverlayChip }
)

internal val historyMenuItemFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    ),
    custom = { method, classDef ->
        method.containsLiteralInstruction(historyMenuItem) &&
            classDef.methods.count() == 5
    }
)

internal val historyMenuItemOfflineTabFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    ),
    custom = { method, _ ->
        method.containsLiteralInstruction(historyMenuItem) &&
            method.containsLiteralInstruction(offlineSettingsMenuItem)
    }
)

internal val searchActionViewFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.containsLiteralInstruction(searchButton) &&
                classDef.type.endsWith("/SearchActionProvider;")
    }
)

internal val topBarMenuItemImageViewFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    custom = customLiteral { topBarMenuItemImageView }
)
