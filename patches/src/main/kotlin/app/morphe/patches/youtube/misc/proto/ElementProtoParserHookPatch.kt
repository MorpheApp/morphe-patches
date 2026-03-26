/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */
package app.morphe.patches.youtube.misc.proto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.cloneMutable

private lateinit var elementProtoParserMethod: MutableMethod

val elementProtoParserHookPatch = bytecodePatch(
    description = "Hook to modify the proto message class, which can only be accessed through reflection.",
) {
    dependsOn(
        sharedExtensionPatch,
        fixProtoLibraryPatch,
    )

    execute {

        NewElementProtoParserFingerprint.let {
            it.method.apply {
                // Not enough registers in the method. Clone the method and use the
                // original method as an intermediate to call extension code.

                // Copy the method.
                val helperMethod = cloneMutable(name = "patch_parseNewElement")

                // Add the method.
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

                elementProtoParserMethod = this
            }
        }
    }
}

fun hookElement(
    methodDescriptor: String
) = elementProtoParserMethod.addInstructions(
    2,
    """
        invoke-static { p0 }, $methodDescriptor
        move-result-object p0
    """
)
