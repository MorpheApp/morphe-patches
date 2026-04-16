package app.morphe.patches.music.layout.buttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
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
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_overlay_chip")
    )
)

internal object HistoryMenuItemFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "history_menu_item"),
        methodCall(smali = "Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;"),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
    ),
    custom = { _, classDef ->
        classDef.methods.count() == 5 || classDef.methods.count() == 4
    }
)

internal object HistoryMenuItemOfflineTabFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "offline_settings_menu_item"),
        resourceLiteral(ResourceType.ID, "history_menu_item"),
        methodCall(smali = "Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;"),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
    )
)

internal object SearchActionViewFingerprint : Fingerprint(
    definingClass = "/SearchActionProvider;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "search_button")
    )
)

internal object TopBarMenuItemImageViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "top_bar_menu_item_image_view")
    )
)