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
import app.morphe.patches.youtube.misc.playservice.is_21_20_or_greater
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
    val binding = PlaylistRepeatBindingFingerprint.match()
    val headerType = binding.originalClassDef.type
    val header = mutableClassDefBy(headerType)
    val renderState = binding.instructionMatches.last().methodReference
    val navigator = playlistHeaderNavigatorFingerprint(navigatorType)
        .instructionMatches.single().fieldReference
    val panel = playlistHeaderPanelFingerprint(binding.originalMethod)
        .instructionMatches.single().fieldReference
    val repeatView = PlaylistHeaderRepeatViewFingerprint
        .instructionMatches.last().fieldReference
    val shuffleMatch = PlaylistShuffleViewFingerprint
    val shuffleView = shuffleMatch.instructionMatches.last().fieldReference
    check(shuffleView.definingClass == headerType) {
        "Native playlist queue: shuffle and repeat must share the native header"
    }

    val capabilities = PlaylistRepeatCapabilitiesFingerprint
    check(capabilities.originalMethod == binding.originalMethod) {
        "Native playlist queue: repeat capabilities and state must share their native binder"
    }

    val repeatFlags = capabilities.instructionMatches.mapNotNull {
        it.instruction.getReference<FieldReference>()?.takeIf { field -> field.type == "Z" }
    }
    check(repeatFlags.size == 2 && repeatFlags.distinct().size == 2 &&
            repeatFlags.all { it.definingClass == headerType }) {
        "Native playlist queue: repeat capabilities must be distinct fields on the bound header"
    }
    val (repeatAll, repeatOne) = repeatFlags
    // 21.16 installs the listener in the constructor. The supported 21.20+
    // binders instead keep a listener field so they can hide/restore the action.
    val restoreShuffleListener = if (is_21_20_or_greater) {
        val listener = playlistShuffleListenerFingerprint(binding.originalMethod, shuffleView)
            .instructionMatches[2].fieldReference
        """
            iget-object v1, p0, $listener
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setOnClickListener(Landroid/view/View${'$'}OnClickListener;)V
        """
    } else ""

    header.interfaces.add(REPEAT_HEADER_INTERFACE)
    header.addBridgeMethod(
        "patch_getNavigator",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        """
            iget-object v0, p0, $navigator
            return-object v0
        """
    )

    header.addBridgeMethod(
        "patch_getPanel",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        """
            iget-object v0, p0, $panel
            return-object v0
        """
    )

    header.addBridgeMethod(
        "patch_enableRepeat",
        emptyList(),
        "V",
        2,
        """
            const/4 v0, 0x1
            iput-boolean v0, p0, $repeatAll
            iput-boolean v0, p0, $repeatOne
            iget-object v1, p0, $repeatView
            invoke-virtual {v1, v0}, Lcom/google/android/apps/youtube/app/watch/panel/ui/PlaylistLoopButtonView;->setEnabled(Z)V
            return-void
        """
    )

    // Reuse the native selected/loop-one drawable state after an owned panel bind.
    header.addBridgeMethod(
        "patch_setRepeatMode",
        listOf("I"),
        "V",
        1,
        """
            const/4 v0, 0x0
            invoke-virtual {p0, p1, v0}, $renderState
            return-void
        """
    )

    header.addBridgeMethod(
        "patch_setShuffleAvailable",
        listOf("Z"),
        "V",
        2,
        """
            iget-object v0, p0, $shuffleView
            invoke-virtual {v0, p1}, Landroid/widget/ImageView;->setEnabled(Z)V
            # This runs only for the owned local queue. Native binders can hide an
            # unsupported shuffle action and clear its listener; restore both.
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setVisibility(I)V
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setSelected(Z)V
            $restoreShuffleListener
            return-void
        """
    )

    // Preserve the original listener and account-playlist command branch. Only a
    // header bound to our exact local panel consumes this fallback toggle.
    val shufflePress = playlistShufflePressFingerprint(navigator, shuffleView)
    val pressIndex = shufflePress.instructionMatches.first().index
    val headerRegister = (shufflePress.instructionMatches.first().instruction as TwoRegisterInstruction).registerB
    val press = shufflePress.method
    val resultRegister = press.findFreeRegister(pressIndex)
    press.addInstructionsAtControlFlowLabel(
        pressIndex,
        """
            invoke-static/range {v$headerRegister .. v$headerRegister}, $EXTENSION_CLASS->onQueueShuffle($REPEAT_HEADER_INTERFACE)Z
            move-result v$resultRegister
            if-eqz v$resultRegister, :native_shuffle
            return-void

            :native_shuffle
            nop
        """
    )

    // The binder reuses p0 on some exits. Preserve its owner before observing all returns.
    binding.method.cloneParameters().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onRepeatHeaderBound($REPEAT_HEADER_INTERFACE)V"
            )
        }
    }
}
