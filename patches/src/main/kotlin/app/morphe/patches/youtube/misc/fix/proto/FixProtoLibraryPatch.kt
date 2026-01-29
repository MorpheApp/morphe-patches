package app.morphe.patches.youtube.misc.fix.proto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Some classes that exist in YouTube are not merged.
 * Instead of relocating all classes in the extension,
 * Only the methods necessary for the patch to function are added.
 */
internal val fixProtoLibraryPatch = bytecodePatch{
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
    }
}