/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.shared.misc.litho.context

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

const val EXTENSION_CONTEXT_INTERFACE =
    "Lapp/morphe/extension/shared/patches/components/ContextInterface;"

/**
 * Holds the mutable class def of the conversion context class after the patch has run.
 *
 * Only one variant of [createConversionContextPatch] runs per patching session (per target app),
 * so a session-scoped var is safe.
 */
lateinit var conversionContextClassDef: MutableClass
    internal set

/**
 * Shared factory for the ConversionContext patch used by both YouTube and YT Music.
 *
 * Adds the [EXTENSION_CONTEXT_INTERFACE] interface to the app's ConversionContext class (or its
 * abstract superclass in some versions), exposing helper methods that extension code can call to
 * read the identifier and the path-builder StringBuilder via obfuscation-safe names.
 *
 * @param sharedExtensionPatchDep The app-specific `sharedExtensionPatch` (ensures the extension
 *                                classes referenced by [EXTENSION_CONTEXT_INTERFACE] are present).
 */
internal fun createConversionContextPatch(
    sharedExtensionPatchDep: BytecodePatch,
): BytecodePatch = bytecodePatch(
    description = "Hooks the method to use the conversion context in an extension."
) {
    dependsOn(sharedExtensionPatchDep)

    execute {
        val (identifierField, stringBuilderField) = with (ConversionContextToStringFingerprint) {
            conversionContextClassDef = classDef

            Pair(
                method.findFieldFromToString(IDENTIFIER_PROPERTY),
                conversionContextClassDef.fields.single { field -> field.type == "Ljava/lang/StringBuilder;" }
            )
        }

        // The conversionContext class can be used as is in most versions.
        if (conversionContextClassDef.superclass == "Ljava/lang/Object;") {
            conversionContextClassDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_CONTEXT_INTERFACE)

                arrayOf(
                    Triple(
                        "patch_getIdentifier",
                        "Ljava/lang/String;",
                        identifierField
                    ),
                    Triple(
                        "patch_getPathBuilder",
                        "Ljava/lang/StringBuilder;",
                        stringBuilderField
                    )
                ).forEach { (interfaceMethodName, interfaceMethodReturnType, classFieldReference) ->
                    methods.add(
                        ImmutableMethod(
                            type,
                            interfaceMethodName,
                            listOf(),
                            interfaceMethodReturnType,
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(2),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    iget-object v0, p0, $classFieldReference
                                    return-object v0
                                """
                            )
                        }
                    )
                }
            }
        } else {
            // In some special versions, such as YouTube 20.41, it inherits from an abstract class,
            // in which case a helper method is added to the abstract class.

            // Since fields cannot be accessed directly in an abstract class, abstract methods are linked.
            val conversionContextIdentifierFingerprint = Fingerprint(
                definingClass = conversionContextClassDef.type,
                parameters = listOf(),
                returnType = "Ljava/lang/String;",
                filters = listOf(
                    fieldAccess(
                        opcode = Opcode.IGET_OBJECT,
                        reference = identifierField
                    ),
                    opcode(
                        opcode = Opcode.RETURN_OBJECT,
                        location = MatchAfterImmediately()
                    )
                )
            )
            val conversionContextStringBuilderFingerprint = Fingerprint(
                definingClass = conversionContextClassDef.type,
                parameters = listOf(),
                returnType = "Ljava/lang/StringBuilder;",
                filters = listOf(
                    fieldAccess(
                        opcode = Opcode.IGET_OBJECT,
                        reference = stringBuilderField
                    ),
                    opcode(
                        opcode = Opcode.RETURN_OBJECT,
                        location = MatchAfterImmediately()
                    )
                )
            )

            val stringBuilderMethodName = conversionContextStringBuilderFingerprint.method.name
            val identifierMethodName = conversionContextIdentifierFingerprint.method.name

            conversionContextClassDef = mutableClassDefBy(conversionContextClassDef.superclass!!)

            conversionContextClassDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                interfaces.add(EXTENSION_CONTEXT_INTERFACE)

                arrayOf(
                    Triple(
                        "patch_getIdentifier",
                        "Ljava/lang/String;",
                        identifierMethodName
                    ),
                    Triple(
                        "patch_getPathBuilder",
                        "Ljava/lang/StringBuilder;",
                        stringBuilderMethodName
                    )
                ).forEach { (interfaceMethodName, interfaceMethodReturnType, classMethodName) ->
                    methods.add(
                        ImmutableMethod(
                            type,
                            interfaceMethodName,
                            listOf(),
                            interfaceMethodReturnType,
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(2),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    invoke-virtual {p0}, $type->$classMethodName()$interfaceMethodReturnType
                                    move-result-object v0
                                    return-object v0
                                """
                            )
                        }
                    )
                }
            }
        }
    }
}
