package app.morphe.patches.music.layout.buttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MediaRouteButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    strings = listOf("MediaRouteButton")
)

internal object PlayerOverlayChipFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    custom = customLiteral { playerOverlayChip }
)

internal object HistoryMenuItemFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    ),
    custom = { method, classDef ->
        method.containsLiteralInstruction(historyMenuItem) &&
                (classDef.methods.count() == 5 || classDef.methods.count() == 4)
    }
)

internal object HistoryMenuItemOfflineTabFingerprint : Fingerprint(
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

internal object SearchActionViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.containsLiteralInstruction(searchButton) &&
                classDef.type.endsWith("/SearchActionProvider;")
    }
)

internal object TopBarMenuItemImageViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    custom = customLiteral { topBarMenuItemImageView }
)
