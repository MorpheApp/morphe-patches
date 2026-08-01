/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.widesearchbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private object SetWordmarkHeaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/widget/ImageView;"),
    filters = listOf(
        methodCall(returnType = "Z"),
        resourceLiteral(ResourceType.ATTR, "ytPremiumWordmarkHeader"),
        resourceLiteral(ResourceType.ATTR, "ytWordmarkHeader")
    )
)

/**
 * Matches the same method as [app.morphe.patches.youtube.layout.hide.general.YouTubeDoodlesImageViewFingerprint].
 */
private object WideSearchBarLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "action_bar_ringo")
    )
)

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/WideSearchBarLegacyPatch;"

context(patchContext: BytecodePatchContext)
internal fun applyLegacyWideSearchBar() {
    SetWordmarkHeaderFingerprint.instructionMatches.first().getMethodCalled().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
            val register = getInstruction<OneRegisterInstruction>(index).registerA

            addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->enableWideSearchbar(Z)Z
                    move-result v$register
                """
            )
        }
    }

    // Fix missing left padding when using wide searchbar.
    WideSearchBarLayoutFingerprint.method.apply {
        findInstructionIndicesReversedOrThrow(
            methodCall(
                definingClass = "Landroid/view/LayoutInflater;",
                name = "inflate"
            )
        ).forEach { inflateIndex ->
            val register = getInstruction<OneRegisterInstruction>(inflateIndex + 1).registerA

            addInstruction(
                inflateIndex + 2,
                "invoke-static { v$register }, $EXTENSION_CLASS->setActionBar(Landroid/view/View;)V"
            )
        }
    }
}
