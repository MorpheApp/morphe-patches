/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.proto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.cloneMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.lang.ref.WeakReference

internal lateinit var parseByteArrayMethod: MutableMethod

internal lateinit var immutableMethodRef : WeakReference<MethodReference>
internal lateinit var mutableCopyMethodRef : WeakReference<MethodReference>

/**
 * Some classes that exist in YouTube are not merged.
 * Instead of relocating all classes in the extension,
 * Only the methods necessary for the patch to function are added.
 */
internal val fixProtoLibraryPatch = bytecodePatch(
    description = "Fix method not found exception that can occur when proto libraries used in extensions are merged."
) {
    execute {
        EmptyRegistryFingerprint.let {
            it.method.apply {
                it.classDef.methods.add(
                    ImmutableMethod(
                        definingClass,
                        "getEmptyRegistry",
                        emptyList(),
                        "Lcom/google/protobuf/ExtensionRegistryLite;",
                        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                        annotations,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                new-instance v0, Lcom/google/protobuf/ExtensionRegistryLite;
                                invoke-direct {v0}, Lcom/google/protobuf/ExtensionRegistryLite;-><init>()V
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        MessageLiteWriteToFingerprint.let {
            it.method.apply {
                it.classDef.methods.add(
                    cloneMutable(
                        parameters = listOf(
                            ImmutableMethodParameter(
                                "Lcom/google/protobuf/CodedOutputStream;",
                                null,
                                null
                            )
                        )
                    )
                )
            }
        }

        parseByteArrayMethod = ProtobufClassParseByteArrayFingerprint.method

        StreamingDataOuterClassFingerprint.let {
            it.method.apply {
                val immutableMethodIndex = it.instructionMatches.first().index
                val mutableCopyMethodIndex = it.instructionMatches.last().index

                val immutableMethodReference =
                    getInstruction<ReferenceInstruction>(immutableMethodIndex).reference as MethodReference
                val mutableCopyMethodReference =
                    getInstruction<ReferenceInstruction>(mutableCopyMethodIndex).reference as MethodReference

                immutableMethodRef = WeakReference(immutableMethodReference)
                mutableCopyMethodRef = WeakReference(mutableCopyMethodReference)
            }
        }
    }
}