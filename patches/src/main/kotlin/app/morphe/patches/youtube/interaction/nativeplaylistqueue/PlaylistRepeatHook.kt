/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** Keep native repeat handling; turn only the owned queue's shuffle into a list command. */
internal fun installPlaylistRepeat(
    context: BytecodePatchContext,
    navigatorType: String
) = with(context) {
    val binding = PlaylistRepeatBindingFingerprint.matchSingle()
    val headerType = binding.originalClassDef.type
    val header = mutableClassDefBy(headerType)
    val navigator = playlistHeaderNavigatorFingerprint(navigatorType).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!
    val panel = playlistHeaderPanelFingerprint(binding.originalMethod).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!
    val repeatView = PlaylistHeaderRepeatViewFingerprint.matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val shuffleMatch = PlaylistShuffleViewFingerprint.matchSingle()
    val shuffleView = shuffleMatch.instructionMatches.last().instruction.getReference<FieldReference>()!!
    check(shuffleView.definingClass == headerType) {
        "Native playlist queue: shuffle and repeat must share the native header"
    }

    val capabilities = PlaylistRepeatCapabilitiesFingerprint.matchSingle()
    check(capabilities.originalMethod == binding.originalMethod) {
        "Native playlist queue: repeat capabilities and state must share their native binder"
    }

    val repeatAll = capabilities.instructionMatches[0].instruction.getReference<FieldReference>()!!
    val repeatOne = capabilities.instructionMatches[2].instruction.getReference<FieldReference>()!!
    check(repeatAll != repeatOne && repeatAll.definingClass == headerType && repeatOne.definingClass == headerType) {
        "Native playlist queue: repeat capabilities must be distinct fields on the bound header"
    }

    header.interfaces.add(REPEAT_HEADER_INTERFACE)
    header.addBridgeMethod("patch_getNavigator", emptyList(), "Ljava/lang/Object;", 1, """
        iget-object v0, p0, $navigator
        return-object v0
    """)

    header.addBridgeMethod("patch_getPanel", emptyList(), "Ljava/lang/Object;", 1, """
        iget-object v0, p0, $panel
        return-object v0
    """)

    header.addBridgeMethod("patch_enableRepeat", emptyList(), "V", 2, """
        const/4 v0, 0x1
        iput-boolean v0, p0, $repeatAll
        iput-boolean v0, p0, $repeatOne
        iget-object v1, p0, $repeatView
        invoke-virtual {v1, v0}, Lcom/google/android/apps/youtube/app/watch/panel/ui/PlaylistLoopButtonView;->setEnabled(Z)V
        return-void
    """)

    header.addBridgeMethod("patch_setShuffleAvailable", listOf("Z"), "V", 1, """
        iget-object v0, p0, $shuffleView
        invoke-virtual {v0, p1}, Landroid/widget/ImageView;->setEnabled(Z)V
        const/4 p1, 0x0
        invoke-virtual {v0, p1}, Landroid/widget/ImageView;->setSelected(Z)V
        return-void
    """)

    // Preserve the original listener and account-playlist command branch. Only a
    // header bound to our exact local panel consumes this fallback toggle.
    val shufflePress = playlistShufflePressFingerprint(navigator, shuffleView).matchSingle()
    val pressIndex = shufflePress.instructionMatches.first().index
    val headerRegister = (shufflePress.instructionMatches.first().instruction as TwoRegisterInstruction).registerB
    val press = shufflePress.method
    val resultRegister = press.findFreeRegister(pressIndex)
    press.addInstructionsAtControlFlowLabel(pressIndex, """
        invoke-static/range {v$headerRegister .. v$headerRegister}, $EXTENSION_CLASS->onQueueShuffle($REPEAT_HEADER_INTERFACE)Z
        move-result v$resultRegister
        if-eqz v$resultRegister, :native_shuffle
        return-void

        :native_shuffle
        nop
    """)

    // The binder reuses p0 on some exits. Preserve its owner before observing all returns.
    binding.method.cloneParameters().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructionsAtControlFlowLabel(index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onRepeatHeaderBound($REPEAT_HEADER_INTERFACE)V")
        }
    }
}
