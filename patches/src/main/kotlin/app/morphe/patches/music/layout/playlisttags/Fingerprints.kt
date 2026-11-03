/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4304
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
import app.morphe.patches.music.misc.androidauto.HandleMusicReloadShelfEventFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Constructor of a Library presenter, matched at the call to the super constructor.
 */
internal fun libraryPresenterConstructorFingerprint(presenterClass: ClassDef) = Fingerprint(
    definingClass = presenterClass.type,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = presenterClass.superclass, name = "<init>")
    )
)

/**
 * Binds a new response to the Library list presenter. It keeps the renderer and the items it builds in fields.
 *
 * @param itemsMethod Static method that builds the items of the renderer.
 */
internal fun libraryListBindFingerprint(itemsMethod: Method) = Fingerprint(
    definingClass = itemsMethod.definingClass,
    returnType = "V",
    parameters = listOf(itemsMethod.parameterTypes.first().toString(), "Z"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = itemsMethod.parameterTypes.first().toString()
        ),
        methodCall(reference = itemsMethod, location = MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Builds the items of the Library grid view, which has its own presenter and renderer.
 * The list view uses the presenter of GridRendererItemsFingerprint.
 *
 * Some items are wrapped by a method of an interface, which is the first instruction match.
 */
internal object LibraryGridItemsFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf("L"), returnType = "L"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("Unsupported renderer in GridRenderer")
    )
)

/**
 * Constructor of the wrapper of a Library grid item, which keeps the wrapped proto in a field.
 */
internal fun libraryGridItemWrapperConstructorFingerprint(wrapperType: String, messageType: String) = Fingerprint(
    definingClass = wrapperType,
    name = "<init>",
    returnType = "V",
    parameters = listOf(messageType),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = messageType)
    )
)

/**
 * Lays out the rows of the Library grid again after the configuration changed. It reads the renderer field,
 * clears the rows of the renderer, and lays out the items in rows.
 */
internal object LibraryGridConfigurationChangedFingerprint : Fingerprint(
    classFingerprint = LibraryGridItemsFingerprint,
    returnType = "V",
    parameters = listOf("Landroid/content/res/Configuration;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        methodCall(
            definingClass = "this",
            parameters = listOf("L"),
            returnType = "V",
            location = MatchAfterWithin(2)
        ),
        methodCall(
            definingClass = "this",
            parameters = listOf("Ljava/util/List;", "L"),
            returnType = "V",
            location = MatchAfterWithin(4)
        )
    )
)

/**
 * Removes a playlist from the Library list when the event of a deleted playlist, or a playlist removed
 * from the Library, is posted. The event is the parameter.
 */
internal object HandleDeletePlaylistEventFingerprint : Fingerprint(
    classFingerprint = HandleMusicReloadShelfEventFingerprint,
    name = "handleDeletePlaylistEvent",
    returnType = "V",
    parameters = listOf("L"),
)

/**
 * Constructor of the event of a deleted playlist. The second parameter is the id of the playlist.
 */
internal fun deletePlaylistEventConstructorFingerprint(eventType: String) = Fingerprint(
    definingClass = eventType,
    name = "<init>",
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = "Ljava/lang/String;")
    )
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
