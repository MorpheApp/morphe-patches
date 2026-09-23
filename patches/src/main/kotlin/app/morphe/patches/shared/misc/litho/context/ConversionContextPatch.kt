/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1919
 * https://github.com/MorpheApp/morphe-patches/pull/3120
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.shared.misc.litho.context

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
        val toStringMethod: MutableMethod
        val stringBuilderField: FieldReference
        val identifierField: FieldReference
        val horizontalSwipeField: FieldReference
        val heightConstraint: FieldReference

        with(ConversionContextToStringFingerprint) {
            conversionContextClassDef = classDef
            toStringMethod = method
            stringBuilderField = conversionContextClassDef.fields.single { field ->
                field.type == "Ljava/lang/StringBuilder;"
            }
            identifierField = method.findFieldFromToString(IDENTIFIER_PROPERTY)
            horizontalSwipeField = method.findFieldFromToString(HORIZONTAL_COLLECTION_SWIPE_PROTECTOR_PROPERTY)
            heightConstraint = method.findFieldFromToString(HEIGHT_CONSTRAINT_PROPERTY)
        }

        // Replace toString() to only include information patches care about.
        // Edit: This change is no longer needed as toString() is no longer called on the context.
        toStringMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v2, p0
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                const-string v1, "identifierProperty="
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                iget-object v1, v2, $identifierField
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
                const-string v1, " "
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                iget-object v1, v2, $stringBuilderField
 
                if-eqz v1, :morphe_cc_no_path
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
                :morphe_cc_no_path
                nop
                invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
        )

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
                ),
                Triple(
                    "patch_getHorizontalCollectionSwipeProtector",
                    "Ljava/lang/Object;",
                    horizontalSwipeField
                ),
                Triple(
                    "patch_getHeightConstraint",
                    "Ljava/lang/Integer;",
                    heightConstraint
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
    }
}
