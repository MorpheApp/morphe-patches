/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.playlisttags

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Builds the items of the Library grid view, which has its own presenter and renderer.
 * The list view uses the presenter of GridRendererItemsFingerprint.
 */
internal object LibraryGridItemsFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        string("Unsupported renderer in GridRenderer")
    )
)

/**
 * Removes a playlist from a list when the event of a deleted playlist, or a playlist removed
 * from the Library, is posted. The event is the parameter.
 */
internal object HandleDeletePlaylistEventFingerprint : Fingerprint(
    name = "handleDeletePlaylistEvent",
    returnType = "V",
    parameters = listOf("L"),
)

/**
 * Returns the layout of the flyout menu bottom sheet.
 */
internal object MenuBottomSheetLayoutFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "music_menu_bottom_sheet_dialog_fragment")
    )
)

/**
 * Adds the items of the menu renderer, which is kept in an Object field, to the bottom sheet list.
 */
internal object MenuBottomSheetItemsFingerprint : Fingerprint(
    classFingerprint = MenuBottomSheetLayoutFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/Object;"),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        methodCall("Ljava/util/List;->iterator()Ljava/util/Iterator;", location = MatchAfterWithin(5))
    )
)

internal object MenuBottomSheetOnStartFingerprint : Fingerprint(
    classFingerprint = MenuBottomSheetLayoutFingerprint,
    name = "onStart",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/app/Dialog;")
    )
)

/**
 * Constructor of the sort button shown above lists such as the Library.
 */
internal object SortFilterButtonConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "music_sort_filter_button"),
        methodCall(
            "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;)Landroid/view/View;",
            location = MatchAfterWithin(3)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)
