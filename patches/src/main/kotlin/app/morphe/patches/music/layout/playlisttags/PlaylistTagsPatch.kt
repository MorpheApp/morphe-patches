/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.playlisttags

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
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
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PlaylistTagsPatch;"
private const val EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$LibraryGridPresenter;"
private const val EXTENSION_LIBRARY_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaylistTagsPatch$LibraryPresenter;"

@Suppress("unused")
val playlistTagsPatch = bytecodePatch(
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
        val reloadMethod = findInterfaceMethod(EXTENSION_LIBRARY_PRESENTER_INTERFACE, "patch_reloadLibrary")
        val rebindItemsMethod = findInterfaceMethod(EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE, "patch_rebindItems")
        GridRendererItemsFingerprint.let { it.classDef.hookLibraryListPresenter(it.method, reloadMethod) }
        LibraryGridItemsFingerprint.let { it.classDef.hookLibraryGridPresenter(it.method, rebindItemsMethod) }

        // endregion

        // region Library: forget the tags of deleted playlists.

        // The event keeps the removed item, if any, and the id of the playlist.
        val deleteEventType = HandleDeletePlaylistEventFingerprint.method.parameterTypes.first().toString()
        mutableClassDefBy(deleteEventType).methods.filter { method ->
            method.name == "<init>" && method.parameterTypes.map { type -> type.toString() }.let { types ->
                types.size == 2 && types[1] == "Ljava/lang/String;"
            }
        }.ifEmpty {
            throw PatchException("Could not find the constructor of the deleted playlist event")
        }.forEach { constructor ->
            constructor.addInstruction(
                0,
                "invoke-static { p2 }, $EXTENSION_CLASS->onPlaylistRemoved(Ljava/lang/String;)V"
            )
        }

        // endregion

        // region Flyout menu: add a 'Tags' row to the menu of a playlist.

        val menuField = MenuBottomSheetItemsFingerprint.instructionMatches.first()
            .instruction.getReference<FieldReference>()!!

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

private const val FILTER_LIBRARY_ITEMS =
    "$EXTENSION_CLASS->filterLibraryItems(Ljava/util/List;)Ljava/util/List;"

private fun Method.smaliReference() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.calls(method: Method) =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_DIRECT) &&
            getReference<MethodReference>()?.let { reference ->
                reference.definingClass == method.definingClass && reference.name == method.name &&
                        reference.parameterTypes.map { type -> type.toString() } ==
                        method.parameterTypes.map { type -> type.toString() }
            } == true

/**
 * Filters the items the method returns. Loops exit by branching to the return instructions,
 * so the labels must move to the hooks.
 */
private fun MutableMethod.filterReturnedItems(filterMethod: String = FILTER_LIBRARY_ITEMS) {
    // Added from the last one, so the indexes of the earlier ones stay valid.
    instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.RETURN_OBJECT
    }.map { it.index }.reversed().forEach { index ->
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
 * Registers each new presenter with the extension, and filters the items a presenter restores
 * from the saved state of a previous one.
 */
private fun MutableClass.registerLibraryPresenter(restoredItemsField: FieldReference?) {
    // The constructor may reuse p0 as a scratch register after calling the super constructor,
    // so register the presenter right after that call, using the register it was called with.
    val superclassType = superclass
    methods.filter { method -> method.name == "<init>" }.forEach { constructor ->
        val superInitIndex = constructor.instructions.indexOfFirst { instruction ->
            (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE)
                    && instruction.getReference<MethodReference>()?.let { reference ->
                        reference.name == "<init>" && reference.definingClass == superclassType
                    } == true
        }
        if (superInitIndex < 0) throw PatchException("Could not find the super constructor call of $type")

        val superInit = constructor.getInstruction<Instruction>(superInitIndex)
        val thisRegister = if (superInit is RegisterRangeInstruction) {
            superInit.startRegister
        } else {
            (superInit as FiveRegisterInstruction).registerC
        }

        if (restoredItemsField != null) {
            // Added from the last one, so the indexes of the earlier ones stay valid.
            constructor.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.IPUT_OBJECT &&
                        instruction.getReference<FieldReference>() == restoredItemsField
            }.map { it.index }.reversed().forEach { index ->
                val listRegister = constructor.getInstruction<TwoRegisterInstruction>(index).registerA
                constructor.addInstructions(
                    index,
                    """
                        invoke-static/range { v$listRegister .. v$listRegister }, $FILTER_LIBRARY_ITEMS
                        move-result-object v$listRegister
                    """
                )
            }
        }

        constructor.addInstruction(
            superInitIndex + 1,
            "invoke-static/range { v$thisRegister .. v$thisRegister }, " +
                    "$EXTENSION_CLASS->registerLibraryPresenter(Ljava/lang/Object;)V"
        )
    }
}

/**
 * Hooks the presenter of the Library list view.
 *
 * @param itemsMethod Static method that builds the items from the grid renderer.
 */
private fun MutableClass.hookLibraryListPresenter(itemsMethod: MutableMethod, reloadMethod: Method) {
    val rendererType = itemsMethod.parameterTypes.first().toString()
    itemsMethod.filterReturnedItems()

    // The method that binds a new response, which keeps the renderer and the items in fields.
    val bindMethod = methods.firstOrNull { method ->
        method.returnType == "V" &&
                method.parameterTypes.map { type -> type.toString() } == listOf(rendererType, "Z") &&
                method.instructions.any { instruction -> instruction.calls(itemsMethod) }
    } ?: throw PatchException("Could not find the Library list bind method")

    val rendererField = bindMethod.instructions.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT) return@firstNotNullOfOrNull null
        instruction.getReference<FieldReference>()?.takeIf { reference ->
            reference.definingClass == type && reference.type == rendererType
        }
    } ?: throw PatchException("Could not find the Library list renderer field")

    val itemsCallIndex = bindMethod.instructions.indexOfFirst { instruction -> instruction.calls(itemsMethod) }
    val itemsListField = bindMethod.instructions.drop(itemsCallIndex).firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT) return@firstNotNullOfOrNull null
        instruction.getReference<FieldReference>()?.takeIf { reference ->
            reference.definingClass == type && reference.type == "Ljava/util/List;"
        }
    } ?: throw PatchException("Could not find the Library list items field")

    // Requesting a reload from the server does nothing when the Library has no pending request,
    // so bind the renderer the presenter holds again, as the constructor does. This also clears
    // the headers that binding adds again.
    interfaces.add(EXTENSION_LIBRARY_PRESENTER_INTERFACE)
    addInterfaceMethod(
        reloadMethod,
        3,
        """
            iget-object v0, p0, $rendererField
            if-eqz v0, :no_renderer
            const/4 v1, 0x0
            invoke-direct { p0, v0, v1 }, $type->${bindMethod.name}(${rendererType}Z)V
            :no_renderer
            return-void
        """
    )
    registerLibraryPresenter(itemsListField)
}

/**
 * Hooks the presenter of the Library grid view.
 *
 * The presenter keeps only the last page of items it loaded in its renderer field, so the
 * extension keeps the items of all pages, and rebinds the grid from them after the filter changes.
 *
 * @param itemsMethod Method that builds the items from the grid renderer.
 */
private fun MutableClass.hookLibraryGridPresenter(itemsMethod: MutableMethod, rebindItemsMethod: Method) {
    val rendererType = itemsMethod.parameterTypes.first().toString()

    itemsMethod.filterReturnedItems("$EXTENSION_CLASS->filterGridItems(Ljava/util/List;)Ljava/util/List;")
    itemsMethod.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsBuilding(Ljava/lang/Object;)V"
    )

    val rendererField = fields.firstOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == rendererType
    } ?: throw PatchException("Could not find the Library grid renderer field")

    // The method that rebuilds the grid after an item is removed. It keeps the renderer,
    // clears the rows, builds the items and lays them out in rows.
    val rebuildMethod = methods.firstOrNull { method ->
        method.returnType == "V" &&
                method.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;") &&
                method.instructions.any { instruction -> instruction.calls(itemsMethod) }
    } ?: throw PatchException("Could not find the Library grid rebuild method")

    val rebuildCalls = rebuildMethod.instructions.filter { instruction ->
        instruction.opcode == Opcode.INVOKE_DIRECT
    }.mapNotNull { instruction -> instruction.getReference<MethodReference>() }

    fun findMethod(reference: MethodReference) = methods.first { method ->
        method.name == reference.name &&
                method.parameterTypes.map { type -> type.toString() } ==
                reference.parameterTypes.map { type -> type.toString() }
    }

    // Of the methods called with the renderer, the one that keeps the renderer writes its field.
    val clearRowsMethod = rebuildCalls.filter { reference ->
        reference.definingClass == type && reference.returnType == "V" &&
                reference.parameterTypes.map { parameter -> parameter.toString() } == listOf(rendererType)
    }.map(::findMethod).firstOrNull { method ->
        method.instructions.none { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT && instruction.getReference<FieldReference>() == rendererField
        }
    } ?: throw PatchException("Could not find the Library grid clear rows method")

    val layoutRowsMethod = rebuildCalls.firstOrNull { reference ->
        reference.definingClass == type && reference.returnType == "V" &&
                reference.parameterTypes.map { parameter -> parameter.toString() } ==
                listOf("Ljava/util/List;", rendererType)
    }?.let(::findMethod) ?: throw PatchException("Could not find the Library grid layout rows method")

    clearRowsMethod.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsReset(Ljava/lang/Object;)V"
    )
    layoutRowsMethod.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onGridItemsBound(Ljava/lang/Object;)V"
    )

    // Clears the rows and lays out the items in p1, with the header of the renderer.
    interfaces.add(EXTENSION_LIBRARY_GRID_PRESENTER_INTERFACE)
    addInterfaceMethod(
        rebindItemsMethod,
        3,
        """
            iget-object v0, p0, $rendererField
            if-eqz v0, :no_renderer
            invoke-direct { p0, v0 }, ${clearRowsMethod.smaliReference()}
            invoke-direct { p0, p1, v0 }, ${layoutRowsMethod.smaliReference()}
            :no_renderer
            return-void
        """
    )
    registerLibraryPresenter(null)
}
