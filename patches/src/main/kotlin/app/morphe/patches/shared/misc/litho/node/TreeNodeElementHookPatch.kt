/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.node

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.layout.buttons.action.TreeNodeListFingerprint
import app.morphe.patches.music.layout.buttons.action.TreeNodeListHelperConstructorFingerprint
import app.morphe.patches.shared.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/TreeNodeElementPatch;"
private const val EXTENSION_LITHO_CONTAINER_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/TreeNodeElementPatch$LithoGetBufferContainerInterface;"

private lateinit var componentLoadedMethodRef: WeakReference<MutableMethod>
private lateinit var lazilyConvertedElementLoadedMethodRef: WeakReference<MutableMethod>

/**
 * Shared factory for the tree-node element hook patch used by both YouTube and YT Music.
 *
 * Hooks the tree-node result list from Litho so that patched extensions can inspect (and
 * physically remove entries from) the list before it is converted into rendered components.
 *
 * @param sharedExtensionPatchDep The app-specific `sharedExtensionPatch`.
 * @param conversionContextPatchDep The app-specific `conversionContextPatch`.
 */
internal fun createTreeNodeElementHookPatch(
    sharedExtensionPatchDep: BytecodePatch,
    conversionContextPatchDep: BytecodePatch,
    addLithoContainerInterface: Boolean
): BytecodePatch = bytecodePatch(
    description = "Hooks the tree node element lists to the extension."
) {
    dependsOn(
        sharedExtensionPatchDep,
        conversionContextPatchDep,
    )

    execute {
        TreeNodeResultListFingerprint.method.apply {
            val insertIndex = implementation!!.instructions.lastIndex
            val listRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

            val registerProvider = getFreeRegisterProvider(insertIndex, 1)
            val freeRegister = registerProvider.getFreeRegister()

            addInstructionsAtControlFlowLabel(
                insertIndex,
                """
                    move-object/from16 v$freeRegister, p2
                    invoke-static { v$freeRegister, v$listRegister }, $EXTENSION_CLASS->onTreeNodeResultLoaded(${EXTENSION_CONTEXT_INTERFACE}Ljava/util/List;)V
                """
            )
        }

        val componentLoadedMethod = ComponentPatchFingerprint.method
        componentLoadedMethodRef = WeakReference(componentLoadedMethod)

        val lazilyConvertedElementLoadedMethod = LazilyConvertedElementPatchFingerprint.method
        lazilyConvertedElementLoadedMethodRef = WeakReference(lazilyConvertedElementLoadedMethod)

        fun addLithoContainerInterface(
            clazz: MutableClass,
            reference: FieldReference
        ) {
            clazz.apply {
                interfaces.add(EXTENSION_LITHO_CONTAINER_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getContainer",
                        listOf(),
                        "Ljava/lang/Object;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $reference
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        if (addLithoContainerInterface) {
            TreeNodeListFingerprint.let {
                val field = it.instructionMatches.first().getFieldAccessed()
                addLithoContainerInterface(it.classDef, field)
            }

            // FIXME: This needs an update for 20.51.39 and older.
            TreeNodeListHelperConstructorFingerprint.let {
                val p2 = it.method.p0Register + 2
                val index = it.method.indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IPUT_OBJECT && (this as TwoRegisterInstruction).registerA == p2
                }
                val field = it.method.getInstruction<ReferenceInstruction>(index)
                    .getReference<FieldReference>()!!

                addLithoContainerInterface(it.classDef, field)
            }
        }
    }
}

fun hookTreeNodeResult(
    descriptor: String,
    isLazilyConvertedElement: Boolean = true
) {
    val method = if (isLazilyConvertedElement) lazilyConvertedElementLoadedMethodRef.get()!!
    else componentLoadedMethodRef.get()!!

    method.addInstruction(
        0,
        "invoke-static { p0, p1 }, $descriptor(Ljava/lang/String;Ljava/util/List;)V"
    )
}
