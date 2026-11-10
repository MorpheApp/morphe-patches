/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4304
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.playlisttags

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.misc.androidauto.GridRendererItemsFingerprint
import app.morphe.patches.music.misc.androidauto.addInterfaceMethod
import app.morphe.patches.music.misc.androidauto.findInterfaceMethod
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PlaylistTagsPatch;"
private const val EXTENSION_LIBRARY_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$LibraryPresenter;"
private const val EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$LibraryGridPresenter;"
private const val EXTENSION_MESSAGE_WRAPPER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$MessageWrapper;"

private const val FILTER_LIBRARY_ITEMS =
    "$EXTENSION_CLASS->filterLibraryItems(Ljava/util/List;)Ljava/util/List;"
private const val FILTER_GRID_ITEMS =
    "$EXTENSION_CLASS->filterGridItems(Ljava/util/List;)Ljava/util/List;"

@Suppress("unused")
val playlistTagsPatch = patch(
    name = "Playlist tags",
    description = "Adds an option to tag playlists from their menu, and to filter the Library playlists by tag.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_playlist_tags_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_playlist_tags", summary = true),
                    NonInteractivePreference(
                        key = "morphe_music_playlist_tags_manage",
                        tag = "app.morphe.extension.music.settings.preference.PlaylistTagsPreference",
                        selectable = true,
                        dependency = "morphe_music_playlist_tags",
                    ),
                )
            )
        )

        // region Library: hide the playlists of hidden tags.

        // The list view and the grid view of the Library each have their own presenter.
        hookLibraryListPresenter()
        hookLibraryGridPresenter()

        // endregion

        // region Library: forget the tags of deleted playlists.

        val deleteEventType = HandleDeletePlaylistEventFingerprint.method.parameterTypes.first().toString()
        deletePlaylistEventConstructorFingerprint(deleteEventType).method.addInstruction(
            0,
            "invoke-static { p2 }, $EXTENSION_CLASS->onPlaylistRemoved(Ljava/lang/String;)V"
        )

        // endregion

        // region Flyout menu: add a 'Tags' row to the menu of a playlist.

        val menuField = MenuBottomSheetItemsFingerprint.instructionMatches.first().fieldReference

        MenuBottomSheetOnStartFingerprint.let {
            it.method.apply {
                val dialogIndex = it.instructionMatches.first().index
                val dialogRegister = getInstruction<TwoRegisterInstruction>(dialogIndex).registerA
                val insertIndex = dialogIndex + 1
                val menuRegister = findFreeRegister(insertIndex, dialogRegister)

                addInstructions(
                    insertIndex,
                    """
                        iget-object v$menuRegister, p0, $menuField
                        invoke-static { v$dialogRegister, v$menuRegister }, $EXTENSION_CLASS->onMenuBottomSheetStart(Landroid/app/Dialog;Ljava/lang/Object;)V
                    """
                )
            }
        }

        // endregion

        // region Library: add a tag filter button next to the sort button.

        SortFilterButtonConstructorFingerprint.let {
            it.method.apply {
                val viewIndex = it.instructionMatches.last().index
                val viewRegister = getInstruction<OneRegisterInstruction>(viewIndex).registerA

                addInstruction(
                    viewIndex + 1,
                    "invoke-static/range { v$viewRegister .. v$viewRegister }, " +
                            "$EXTENSION_CLASS->onSortFilterButtonCreated(Landroid/view/View;)V"
                )
            }
        }

        // endregion
    }
}

/**
 * Filters the items the method returns. Loops exit by branching to the return instructions,
 * so the labels must move to the hooks.
 */
private fun MutableMethod.filterReturnedItems(filterMethod: String) {
    findInstructionIndicesReversedOrThrow(opcode(Opcode.RETURN_OBJECT)).forEach { index ->
        val listRegister = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$listRegister .. v$listRegister }, $filterMethod
                move-result-object v$listRegister
            """
        )
    }
}

/**
 * Registers each new presenter with the extension, so it can be rebound after the filter changes.
 *
 * @param restoredItemsField Field the constructor sets to the items restored from the saved state
 *                           of a previous presenter, which are filtered again.
 */
private fun registerLibraryPresenter(
    presenterClass: ClassDef,
    restoredItemsField: FieldReference? = null,
) {
    libraryPresenterConstructorFingerprint(presenterClass).let {
        it.method.apply {
            // Added from the last one, so the index of the super constructor call stays valid.
            restoredItemsField?.let { field ->
                findInstructionIndicesReversedOrThrow(
                    fieldAccess(reference = field, opcode = Opcode.IPUT_OBJECT)
                ).forEach { index ->
                    val listRegister = getInstruction<TwoRegisterInstruction>(index).registerA
                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$listRegister .. v$listRegister }, $FILTER_LIBRARY_ITEMS
                            move-result-object v$listRegister
                        """
                    )
                }
            }

            // The constructor may reuse p0 as a scratch register after calling the super constructor,
            // so register the presenter right after that call, using the register it was called with.
            val superInitMatch = it.instructionMatches.first()
            val thisRegister = superInitMatch.instruction.registersUsed.first()
            addInstruction(
                superInitMatch.index + 1,
                "invoke-static/range { v$thisRegister .. v$thisRegister }, " +
                        "$EXTENSION_CLASS->registerLibraryPresenter(Ljava/lang/Object;)V"
            )
        }
    }
}

/**
 * Hooks the presenter of the Library list view.
 */
private fun hookLibraryListPresenter() {
    val itemsMethod = GridRendererItemsFingerprint.method
    itemsMethod.filterReturnedItems(FILTER_LIBRARY_ITEMS)

    val bindFingerprint = libraryListBindFingerprint(GridRendererItemsFingerprint.originalMethod)
    val bindMethod = bindFingerprint.method
    val rendererField = bindFingerprint.instructionMatches.first().fieldReference
    val itemsField = bindFingerprint.instructionMatches.last().fieldReference

    // Requesting a reload from the server does nothing when the Library has no pending request,
    // so bind the renderer the presenter holds again, as the constructor does. This also clears
    // the headers that binding adds again.
    val presenterClass = GridRendererItemsFingerprint.classDef
    presenterClass.interfaces.add(EXTENSION_LIBRARY_PRESENTER_INTERFACE)
    presenterClass.addInterfaceMethod(
        findInterfaceMethod(EXTENSION_LIBRARY_PRESENTER_INTERFACE, "patch_reloadLibrary"),
        3,
        """
            iget-object v0, p0, $rendererField
            if-eqz v0, :no_renderer
            const/4 v1, 0x0
            invoke-direct { p0, v0, v1 }, $bindMethod
            :no_renderer
            return-void
        """
    )
    registerLibraryPresenter(presenterClass, itemsField)
}

/**
 * Hooks the presenter of the Library grid view.
 *
 * The presenter keeps only the last page of items it loaded in its renderer field, so the
 * extension keeps the items of all pages, and rebinds the grid from them after the filter changes.
 */
private fun hookLibraryGridPresenter() {
    LibraryGridItemsFingerprint.method.apply {
        filterReturnedItems(FILTER_GRID_ITEMS)
        addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsBuilding(Ljava/lang/Object;)V"
        )
    }

    // Some items wrap their proto, so the extension needs a getter to read it.
    val wrapItemMethod = LibraryGridItemsFingerprint.instructionMatches.first().methodReference
    val wrapperType = wrapItemMethod.returnType
    val messageType = wrapItemMethod.parameterTypes.first().toString()
    val messageField = libraryGridItemWrapperConstructorFingerprint(wrapperType, messageType)
        .instructionMatches.first().fieldReference
    mutableClassDefBy(wrapperType).apply {
        interfaces.add(EXTENSION_MESSAGE_WRAPPER_INTERFACE)
        addInterfaceMethod(
            findInterfaceMethod(EXTENSION_MESSAGE_WRAPPER_INTERFACE, "patch_getMessage"),
            1,
            """
                iget-object p0, p0, $messageField
                return-object p0
            """
        )
    }

    // The configuration change method clears the rows and lays out the items again, as the rebind does.
    val (rendererMatch, clearRowsMatch, layOutRowsMatch) = LibraryGridConfigurationChangedFingerprint.instructionMatches
    val rendererField = rendererMatch.fieldReference
    val clearRowsMethod = clearRowsMatch.methodReference
    val layOutRowsMethod = layOutRowsMatch.methodReference

    // Items built after the rows are cleared replace the kept items, and later pages are added to them.
    clearRowsMatch.getMethodCalled().addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsReset(Ljava/lang/Object;)V"
    )
    layOutRowsMatch.getMethodCalled().addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsBound(Ljava/lang/Object;)V"
    )

    val presenterClass = LibraryGridItemsFingerprint.classDef
    presenterClass.interfaces.add(EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE)
    presenterClass.addInterfaceMethod(
        findInterfaceMethod(EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE, "patch_rebindItems"),
        3,
        """
            iget-object v0, p0, $rendererField
            if-eqz v0, :no_renderer
            invoke-direct { p0, v0 }, $clearRowsMethod
            invoke-direct { p0, p1, v0 }, $layOutRowsMethod
            :no_renderer
            return-void
        """
    )
    registerLibraryPresenter(presenterClass)
}
