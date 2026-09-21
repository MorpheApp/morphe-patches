/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.playlistsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PlaylistSearchPatch;"

@Suppress("unused")
val playlistSearchPatch = bytecodePatch(
    name = "Playlist search",
    description = "Adds an option to search inside the playlist that is currently open " +
            "instead of searching all of YouTube.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch
    )

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_playlist_search", summary = true)
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                    "setMainActivity(Landroid/app/Activity;)V",
        )

        BrowseFragmentOnCreateViewFingerprint.let {
            it.method.apply {
                val browseDataIndex = it.instructionMatches.first().index

                val endpointIndex = indexOfFirstInstructionReversedOrThrow(
                    browseDataIndex,
                    fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this")
                )
                val endpointField = getInstruction<ReferenceInstruction>(endpointIndex)
                    .getReference<FieldReference>()!!

                val browseDataExtensionIndex = indexOfFirstInstructionOrThrow(
                    endpointIndex, Opcode.SGET_OBJECT
                )
                val browseDataExtensionField =
                    getInstruction<ReferenceInstruction>(browseDataExtensionIndex)
                        .getReference<FieldReference>()!!

                val browseIdMethod = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
                    returnType = "Ljava/lang/String;",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(
                            opcode = Opcode.SGET_OBJECT,
                            reference = browseDataExtensionField
                        )
                    )
                ).method

                Fingerprint(
                    definingClass = definingClass,
                    returnType = "V",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(
                            opcode = Opcode.IPUT_OBJECT,
                            reference = endpointField
                        )
                    )
                ).method.apply {
                    val insertIndex = indexOfFirstInstructionOrThrow(
                        fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField)
                    ) + 1
                    val freeRegister = findFreeRegister(insertIndex)

                    addInstructions(
                        insertIndex,
                        """
                            invoke-static { p1 }, $browseIdMethod
                            move-result-object v$freeRegister
                            invoke-static { v$freeRegister }, $EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V
                        """
                    )
                }
            }
        }

        SearchResultsFragmentOnCreateViewFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->clearBrowseId()V"
        )

        SearchSubmitFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->searchInPlaylist(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :search_all_of_youtube
                return-void
                :search_all_of_youtube
                nop
            """
        )
    }
}
