/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.loadvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LoadVideoPatch;"

private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$PlayerInterface;"

private const val EXTENSION_MAIN_ACTIVITY_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$MainActivityInterface;"

private const val EXTENSION_INLINE_PLAYBACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$InlinePlaybackInterface;"

internal val loadVideoHookPatch = patch(
    description = "Hooks the player so the extension can dismiss it and open videos."
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val dismissPlayerInnerMethod = MiniAppOpenYtContentCommandEndpointFingerprint
            .instructionMatches.last()
            .getInstruction<ReferenceInstruction>()
            .getReference<MethodReference>()!!
        val openNewVideoParcelableMethod = OpenNewVideoIntentParcelableFingerprint.method
        val openNewVideoParcelableDefiningClass = openNewVideoParcelableMethod.definingClass

        mutableClassDefBy(dismissPlayerInnerMethod.definingClass).apply {
            // Add interface and helper methods to allow extension code to call obfuscated methods.
            // Add methods to access obfuscated player methods.
            addPatchInterface(
                EXTENSION_PLAYER_INTERFACE,
                "patch_dismissPlayer",
                """
                    invoke-virtual { p0 }, $dismissPlayerInnerMethod
                    return-void
                """
            )
            addPatchInterface(
                EXTENSION_PLAYER_INTERFACE,
                "patch_getIntentParcelable",
                """
                    invoke-static { p1 }, $openNewVideoParcelableDefiningClass->${openNewVideoParcelableMethod.name}(Landroid/content/Intent;)$openNewVideoParcelableDefiningClass
                    move-result-object v0
                    return-object v0
                """
            )

            methods.single { method ->
                MethodUtil.isConstructor(method)
            }.apply {
                val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize($EXTENSION_PLAYER_INTERFACE)V"
                )
            }
        }

        // Once the player is dismissed, a video in the feed below it can start playing
        // and take the place of the video opened next, so the extension blocks it.
        val blockInlinePlaybackMethod = BlockInlinePlaybackFingerprint.method
        mutableClassDefBy(blockInlinePlaybackMethod.definingClass).apply {
            val unblockInlinePlaybackMethod = UnblockInlinePlaybackFingerprint.match(this).method

            mapOf(
                "patch_blockInlinePlayback" to blockInlinePlaybackMethod,
                "patch_unblockInlinePlayback" to unblockInlinePlaybackMethod
            ).forEach { (name, method) ->
                addPatchInterface(
                    EXTENSION_INLINE_PLAYBACK_INTERFACE,
                    name,
                    """
                        invoke-virtual { p0, p1 }, $method
                        return-void
                    """
                )
            }

            methods.single { method ->
                MethodUtil.isConstructor(method)
            }.apply {
                val index = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                            "initializeInlinePlayback($EXTENSION_INLINE_PLAYBACK_INTERFACE)V"
                )
            }
        }

        // Activity.onNewIntent() is protected, so the extension calls it through a public method.
        mutableClassDefBy(YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE).apply {
            addPatchInterface(
                EXTENSION_MAIN_ACTIVITY_INTERFACE,
                "patch_onNewIntent",
                """
                    invoke-virtual { p0, p1 }, $type->onNewIntent(Landroid/content/Intent;)V
                    return-void
                """
            )
        }

        BackButtonFinishActivityOnNewVideoIntentFingerprint(
            AppWatchDescriptorToStringFingerprint.originalClassDef.type
        ).method.returnEarly()
    }
}
