/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/** Observe the native playlist title after inflation and model rebinding. */
internal fun installPlaylistTitle(context: BytecodePatchContext) = with(context) {
    val titleMatch = PlaylistTitleFingerprint.match()
    val headerType = titleMatch.originalClassDef.type
    val titleStore = titleMatch.instructionMatches.last()
    val title = titleStore.fieldReference
    val panelType = PlaylistModelDescriptionFingerprint.match().originalMethod
        .findFieldFromToString(", playlistPanel=").type
    val binding = playlistTitleBindingFingerprint(headerType, panelType).match()
    val panel = binding.instructionMatches.single().fieldReference

    mutableClassDefBy(headerType).apply {
        interfaces.add(PLAYLIST_TITLE_INTERFACE)
        addBridgeMethod(
            "patch_getPanel",
            emptyList(),
            "Ljava/lang/Object;",
            1,
            """
                iget-object v0, p0, $panel
                return-object v0
            """
        )

        addBridgeMethod(
            "patch_getTitle",
            emptyList(),
            "Landroid/widget/TextView;",
            1,
            """
                iget-object v0, p0, $title
                return-object v0
            """
        )
    }

    // Observe initial inflation once, rather than every call to the cached-view getter.
    // Native model binding can happen earlier, while no TextView exists yet.
    val receiver = (titleStore.instruction as TwoRegisterInstruction).registerB
    titleMatch.method.addInstruction(
        titleStore.index + 1,
        "invoke-static/range {v$receiver .. v$receiver}, $EXTENSION_CLASS->onPlaylistTitleBound($PLAYLIST_TITLE_INTERFACE)V"
    )

    binding.method.cloneParameters().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onPlaylistTitleBound($PLAYLIST_TITLE_INTERFACE)V"
            )
        }
    }
}
