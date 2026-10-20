/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.proto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.util.cloneMutable
import app.morphe.util.fiveRegisters
import java.lang.ref.WeakReference

private var elementprotoParserInsertIndexStep = 2
private var elementprotoParserInsertIndex = elementprotoParserInsertIndexStep
private lateinit var elementProtoParserMethodRef: WeakReference<MutableMethod>

/**
 * Shared factory for the element proto parser hook used by both YouTube and YT Music.
 *
 * Hooks the generic proto parser method so that patched extensions can inspect (and modify)
 * the raw byte buffer of every parsed proto element.
 *
 * @param sharedExtensionPatchDep The app-specific `sharedExtensionPatch`.
 * @param readsResourcesWithParser Whether the inline resources of the responses, such as the templates
 *                                 and the JavaScript modules, can be read with the parser (YouTube 21.40+).
 *                                 Evaluated lazily inside execute so version flags are already set.
 * @param block Callback to add app-specific dependencies, such as versionCheckPatch.
 */
internal fun createElementProtoParserHookPatch(
    sharedExtensionPatchDep: BytecodePatch,
    readsResourcesWithParser: () -> Boolean = { false },
    block: BytecodePatchBuilder.() -> Unit = {},
): BytecodePatch = bytecodePatch(
    description = "Hook to modify the proto message class, which can only be accessed through reflection.",
) {
    dependsOn(
        sharedExtensionPatchDep,
        fixProtoLibraryPatch,
    )

    block()

    execute {
        NewElementProtoParserFingerprint.let {
            it.method.apply {
                // Not enough registers in the method. Clone the method and use the original
                // method as an intermediate to call extension code.
                val helperMethod = cloneMutable(name = "patch_parseNewElement")

                it.classDef.methods.add(helperMethod)

                addInstructions(
                    0,
                    """
                        # Invoke the copied method (helper method).
                        invoke-static { p0 }, $helperMethod
                        move-result-object p0

                        # Since the copied method (helper method) has already been invoked, it just returns.
                        return-object p0
                    """
                )

                elementProtoParserMethodRef = WeakReference(this)

                if (readsResourcesWithParser()) {
                    // When a server flag is on, the inline resources of the responses are read with
                    // this parser. They are not elements, so they use the helper method,
                    // which has no element hooks.
                    resourceBytesFingerprint(this).let { resource ->
                        resource.method.apply {
                            val index = resource.instructionMatches.first().index

                            replaceInstruction(
                                index,
                                "invoke-static { ${fiveRegisters(index)} }, $helperMethod"
                            )
                        }
                    }
                }
            }
        }
    }
}

fun hookElement(
    methodDescriptor: String
) {
    elementProtoParserMethodRef.get()!!.addInstructions(
        elementprotoParserInsertIndex,
        """
            invoke-static { p0 }, $methodDescriptor([B)[B
            move-result-object p0
        """
    )

    elementprotoParserInsertIndex += elementprotoParserInsertIndexStep
}
