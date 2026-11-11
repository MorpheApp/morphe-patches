/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patches.music.layout.hide.flyoutmenu.MenuItemFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MusicOfflineVideoEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf("Object is not an offlineable video: %s"),
    filters = listOf(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL),
            parameters = listOf("Ljava/lang/String;", "L", "L", "L"),
            returnType = "V"
        )
    )
)

internal object MusicOfflinePlaylistEndpointFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;"),
    strings = listOf("Object is not an offlineable playlist: %s")
)

internal object SaveOfflinePlaylistCallFingerprint : Fingerprint(
    classFingerprint = MusicOfflinePlaylistEndpointFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/util/Map;", "L"),
    filters = listOf(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL_RANGE),
            parameters = listOf("Ljava/lang/String;", "L", "L", "L", "L"),
            returnType = "V"
        )
    )
)

internal object MusicOfflineUpsellResponseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("L"), // Response.
        literal(131899791), // Upsell renderer of the response.
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"), // Endpoint command.
        string("com.google.android.libraries.youtube.logging.interaction_logger")
    )
)

internal object MusicOfflinePlayerResponseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        string("Some error occurred when reading from the cache player response"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * Layout of the menu bottom sheet of songs and videos.
 */
internal object MusicMenuBottomSheetLayoutFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "music_menu_bottom_sheet_dialog_fragment")
    )
)

/**
 * Creates the items of the menu bottom sheet of songs and videos, such as the menus of the downloads list,
 * from the items of the menu renderer.
 */
internal object MusicMenuBottomSheetItemsFingerprint : Fingerprint(
    classFingerprint = MusicMenuBottomSheetLayoutFingerprint,
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        // Menu renderer of the bottom sheet.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/Object;"),
        methodCall(smali = "Ljava/util/List;->iterator()Ljava/util/Iterator;"),
        methodCall(smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;"),
        // Menu item renderer.
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(3)),
        // Adds it to the items of the bottom sheet.
        methodCall(
            name = "add",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Click of an item of a native menu.
 */
internal object MenuItemClickFingerprint : Fingerprint(
    classFingerprint = MenuItemFingerprint,
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        // Menu item renderer of the item.
        fieldAccess(definingClass = "this", opcode = Opcode.IGET_OBJECT)
    )
)
