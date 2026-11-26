/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.proto

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.util.cloneMutable
import app.morphe.util.fiveRegisters
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.lang.ref.WeakReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/ElementProtoParserPatch;"

private const val ELEMENT_PROTO_PARSER_INSERT_INDEX_STEP = 2
private var elementprotoParserInsertIndex = ELEMENT_PROTO_PARSER_INSERT_INDEX_STEP
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
 * @param reusesHookedElements Whether the bytes hooked when the element converter parses an element
 *                             for the RenderNext check are used again by the converted element,
 *                             so the element hooks run once for each element (YouTube).
 * @param block Callback to add app-specific dependencies, such as versionCheckPatch.
 */
internal fun createElementProtoParserHookPatch(
    sharedExtensionPatchDep: Patch,
    readsResourcesWithParser: () -> Boolean = { false },
    reusesHookedElements: Boolean = false,
    block: PatchDefinition.() -> Unit = {},
): Patch = patch(
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

                if (reusesHookedElements) {
                    // The element converter parses each element for the RenderNext check, and the
                    // converted element parses it again right after. The element hooks are inserted
                    // between these instructions.
                    addInstructionsWithLabels(
                        elementprotoParserInsertIndex,
                        """
                            invoke-static { p0 }, $EXTENSION_CLASS->getHookedElement([B)[B
                            move-result-object v0
                            if-eqz v0, :hook
                            return-object v0
                            :hook
                            move-object v1, p0
                            invoke-static { v1, p0 }, $EXTENSION_CLASS->setHookedElement([B[B)V
                        """
                    )
                    elementprotoParserInsertIndex = indexOfFirstInstructionOrThrow {
                        getReference<MethodReference>()?.name == "setHookedElement"
                    }

                    elementConverterFingerprint(RenderNextEnablementCheckFingerprint.method).let { converter ->
                        converter.method.addInstruction(
                            converter.instructionMatches.first().index,
                            "invoke-static { }, $EXTENSION_CLASS->onRenderNextCheck()V"
                        )
                    }
                }

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

    elementprotoParserInsertIndex += ELEMENT_PROTO_PARSER_INSERT_INDEX_STEP
}
