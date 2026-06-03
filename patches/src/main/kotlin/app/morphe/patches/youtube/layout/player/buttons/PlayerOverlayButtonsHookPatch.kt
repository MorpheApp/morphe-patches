/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.buttons

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.youtube.layout.miniplayer.EXTENSION_CLASS
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var exploderButtonMethodRef : WeakReference<MutableMethod>
private var exploderButtonInsertIndex = -1
private var exploderButtonInsertRegister = -1

fun addPlayerBottomButton(descriptor: String) {
    exploderButtonMethodRef.get()!!.addInstruction(
        exploderButtonInsertIndex++,
        "invoke-static { v$exploderButtonInsertRegister }, $descriptor->initializeButton(Landroid/view/View;)V"
    )
}

internal val playerOverlayButtonsHookPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch, // Used by fingerprints.
    )

    execute {
        ExploderUIFullscreenButtonFingerprint.let {
            it.method.apply {
                exploderButtonMethodRef = WeakReference(it.method)
                val index = it.instructionMatches[1].index
                exploderButtonInsertRegister = getInstruction<OneRegisterInstruction>(index).registerA
                exploderButtonInsertIndex = index + 1

                // Fix the fullscreen button tint when the minimal miniplayer type is selected.
                // The minimal type forces a theme where ytOverlayButtonPrimary resolves to gray
                // instead of white, making the fullscreen button appear gray instead of white.
                addInstruction(
                    index + 1,
                    "invoke-static { v$exploderButtonInsertRegister }, $EXTENSION_CLASS->fixMinimalMiniplayerFullscreenButtonTint(Landroid/view/View;)V"
                )
            }
        }
    }
}
