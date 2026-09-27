/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.buttons

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.addon.EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

context(patchContext: BytecodePatchContext)
fun addPlayerBottomButton(extensionClass: String, extensionMethod: String = "initializeButton") {
    // Update the insertion index after each add, as other patches
    // can insert code before the insertion point.
    ExploderUIFullscreenButtonFingerprint.let {
        it.clearMatch()
        ExploderUIFullscreenButtonParentFingerprint.clearMatch() // FIXME: remove after bumping patcher.
        val lastMatch = it.instructionMatches.last()
        val register = lastMatch.getInstruction<OneRegisterInstruction>().registerA

        it.method.addInstruction(
            lastMatch.index + 1,
            "invoke-static { v$register }, $extensionClass->$extensionMethod(Landroid/view/View;)V"
        )
    }
}

internal val playerOverlayButtonsHookPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch
    )

    execute {
        addPlayerBottomButton(
            "Lapp/morphe/extension/youtube/videoplayer/PlayerOverlayButton;"
        )

        // Buttons of add-on patch bundles, which cannot add a button of their own.
        addPlayerBottomButton(EXTENSION_ADD_ON_API_CLASS_DESCRIPTOR)
    }
}
