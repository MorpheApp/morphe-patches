/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.loadvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LoadVideoPatch;"

private const val EXTENSION_PLAYER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/LoadVideoPatch$PlayerInterface;"

internal val loadVideoHookPatch = bytecodePatch(
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
            interfaces.add(EXTENSION_PLAYER_INTERFACE)
            // Add methods to access obfuscated player methods.
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_dismissPlayer",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $dismissPlayerInnerMethod
                            return-void
                        """
                    )
                }
            )
            methods.add(
                ImmutableMethod(
                    type,
                    "patch_getIntentParcelable",
                    listOf(ImmutableMethodParameter("Landroid/content/Intent;", null, null)),
                    "Landroid/os/Parcelable;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-static { p1 }, $openNewVideoParcelableDefiningClass->${openNewVideoParcelableMethod.name}(Landroid/content/Intent;)$openNewVideoParcelableDefiningClass
                            move-result-object v0
                            return-object v0
                        """
                    )
                }
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

        BackButtonFinishActivityOnNewVideoIntentFingerprint.method.addInstruction(
            0,
            "return-void"
        )
    }
}
