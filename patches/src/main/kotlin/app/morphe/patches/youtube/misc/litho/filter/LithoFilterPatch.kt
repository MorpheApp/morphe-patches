@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_19_25_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_05_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.ConversionContextFingerprintToString
import app.morphe.util.FreeRegisterProvider
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.returnLate
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

lateinit var addLithoFilter: (String) -> Unit
    private set

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/components/LithoFilterPatch;"

val lithoFilterPatch = bytecodePatch(
    description = "Hooks the method which parses the bytes into a ComponentContext to filter components.",
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    var filterCount = 0

    /**
     * The following patch inserts a hook into the method that parses the bytes into a ComponentContext.
     * This method contains a StringBuilder object that represents the pathBuilder of the component.
     * The pathBuilder is used to filter components by their path.
     *
     * Additionally, the method contains a reference to the component's identifier.
     * The identifier is used to filter components by their identifier.
     *
     * The protobuf buffer is passed along from a different injection point before the filtering occurs.
     * The buffer is a large byte array that represents the component tree.
     * This byte array is searched for strings that indicate the current component.
     *
     * All modifications done here must allow all the original code to still execute
     * even when filtering, otherwise memory leaks or poor app performance may occur.
     *
     * The following pseudocode shows how this patch works:
     *
     * class SomeOtherClass {
     *    // Called before ComponentContextParser.parseComponent() method.
     *    public void someOtherMethod(ByteBuffer byteBuffer) {
     *        ExtensionClass.setProtoBuffer(byteBuffer); // Inserted by this patch.
     *        ...
     *   }
     * }
     *
     * class ComponentContextParser {
     *    public Component parseComponent() {
     *        ...
     *
     *        if (extensionClass.shouldFilter()) {  // Inserted by this patch.
     *            return emptyComponent;
     *        }
     *        return originalUnpatchedComponent; // Original code.
     *    }
     * }
     */
    execute {
        // Remove dummy filter from extenion static field
        // and add the filters included during patching.
        LithoFilterFingerprint.method.apply {
            removeInstructions(2, 4) // Remove dummy filter.

            addLithoFilter = { classDescriptor ->
                addInstructions(
                    2,
                    """
                        new-instance v1, $classDescriptor
                        invoke-direct { v1 }, $classDescriptor-><init>()V
                        const/16 v2, ${filterCount++}
                        aput-object v1, v0, v2
                    """
                )
            }
        }

        // region Pass the buffer into extension.

        if (is_20_22_or_greater) {
            // Hook method that bridges between UPB buffer native code and FB Litho.
            // Method is found in 19.25+, but is forcefully turned off for 20.21 and lower.
            ProtobufBufferReferenceFingerprint.let {
                // Hook the buffer after the call to jniDecode().
                it.method.addInstruction(
                    it.instructionMatches.last().index + 1,
                    "invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->setProtoBuffer([B)V",
                )
            }
        }

        // Legacy Non native buffer.
        ProtobufBufferReferenceLegacyFingerprint.method.addInstruction(
            0,
            "invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->setProtoBuffer(Ljava/nio/ByteBuffer;)V",
        )

        // endregion


        // region Modify the create component method and
        // if the component is filtered then return an empty component.

        // Find the identifier/path fields of the conversion context.

        val conversionContextIdentifierField = ConversionContextFingerprintToString.method
            .findFieldFromToString("identifierProperty=")

        val conversionContextPathBuilderField = ConversionContextFingerprintToString.originalClassDef
            .fields.single { field -> field.type == "Ljava/lang/StringBuilder;" }

        // Find class and methods to create an empty component.
        val builderMethodDescriptor = EmptyComponentFingerprint.classDef.methods.single { method ->
            // The only static method in the class.
            AccessFlags.STATIC.isSet(method.accessFlags)
        }

        val emptyComponentField = classDefBy(builderMethodDescriptor.returnType).fields.single()

        // Find the method call that gets the value of 'buttonViewModel.accessibilityId'.
        val accessibilityIdMethod = with(AccessibilityIdFingerprint) {
            val index = instructionMatches.first().index
            method.getInstruction<ReferenceInstruction>(index).reference as MethodReference
        }

        // There's a method in the same class that gets the value of 'buttonViewModel.accessibilityText'.
        // As this class is abstract, we need to find another method that uses a method call.
        val accessibilityTextFingerprint = Fingerprint(
            returnType = "V",
            filters = listOf(
                methodCall(
                    opcode = Opcode.INVOKE_INTERFACE,
                    parameters = listOf(),
                    returnType = "Ljava/lang/String;"
                ),
                methodCall(
                    reference = accessibilityIdMethod,
                    location = MatchAfterWithin(5)
                )
            ),
            custom = { method, _ ->
                // 'public final synthetic' or 'public final bridge synthetic'.
                AccessFlags.SYNTHETIC.isSet(method.accessFlags)
            }
        )

        // Find the method call that gets the value of 'buttonViewModel.accessibilityText'.
        val accessibilityTextMethod = with (accessibilityTextFingerprint) {
            val index = instructionMatches.first().index
            method.getInstruction<ReferenceInstruction>(index).reference as MethodReference
        }

        ComponentCreateFingerprint.method.apply {
            val insertIndex = indexOfFirstInstructionOrThrow(Opcode.RETURN_OBJECT)


            // We can directly access the class related with the buttonViewModel from this method.
            // This is within 10 lines of insertIndex.
            val buttonViewModelIndex = indexOfFirstInstructionReversedOrThrow(insertIndex) {
                opcode == Opcode.CHECK_CAST &&
                        getReference<TypeReference>()?.type == accessibilityIdMethod.definingClass
            }
            val buttonViewModelRegister =
                getInstruction<OneRegisterInstruction>(buttonViewModelIndex).registerA
            val accessibilityIdIndex = buttonViewModelIndex + 2

            // This is an index that checks if there is accessibility-related text.
            // This is within 10 lines of buttonViewModelIndex.
            val nullCheckIndex = indexOfFirstInstructionReversedOrThrow(
                buttonViewModelIndex, Opcode.IF_EQZ
            )

            val registerProvider = getFreeRegisterProvider(
                insertIndex, 3, buttonViewModelRegister
            )
            val freeRegister = registerProvider.getFreeRegister()
            val identifierRegister = registerProvider.getFreeRegister()
            val pathRegister = registerProvider.getFreeRegister()

            // We need to find a free register to store the accessibilityId and accessibilityText.
            // This is before the insertion index.
            val accessibilityRegisterProvider = getFreeRegisterProvider(
                nullCheckIndex,
                2,
                registerProvider.getUsedAndUnAvailableRegisters()
            )
            val accessibilityIdRegister = accessibilityRegisterProvider.getFreeRegister()
            val accessibilityTextRegister = accessibilityRegisterProvider.getFreeRegister()

            addInstructionsAtControlFlowLabel(
                insertIndex,
                """
                    move-object/from16 v$freeRegister, p2
                    
                    # 20.41 field is the abstract superclass.
                    # Verify it's the expected subclass just in case. 
                    instance-of v$identifierRegister, v$freeRegister, ${ConversionContextFingerprintToString.classDef.type}
                    if-eqz v$identifierRegister, :unfiltered
                    
                    iget-object v$identifierRegister, v$freeRegister, $conversionContextIdentifierField
                    iget-object v$pathRegister, v$freeRegister, $conversionContextPathBuilderField
                    invoke-static { v$identifierRegister, v$accessibilityIdRegister, v$accessibilityTextRegister, v$pathRegister }, $EXTENSION_CLASS_DESCRIPTOR->isFiltered(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/StringBuilder;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :unfiltered
                    
                    # Return an empty component
                    move-object/from16 v$freeRegister, p1
                    invoke-static { v$freeRegister }, $builderMethodDescriptor
                    move-result-object v$freeRegister
                    iget-object v$freeRegister, v$freeRegister, $emptyComponentField
                    return-object v$freeRegister
        
                    :unfiltered
                    nop
                """
            )

            // If there is text related to accessibility, get the accessibilityId and accessibilityText.
            addInstructions(
                accessibilityIdIndex,
                    """
                        # Get accessibilityId
                        invoke-interface { v$buttonViewModelRegister }, $accessibilityIdMethod
                        move-result-object v$accessibilityIdRegister
                        
                        # Get accessibilityText
                        invoke-interface { v$buttonViewModelRegister }, $accessibilityTextMethod
                        move-result-object v$accessibilityTextRegister
                """
            )

            // If there is no accessibility-related text,
            // both accessibilityId and accessibilityText use empty values.
            addInstructions(
                nullCheckIndex,
                """
                    const-string v$accessibilityIdRegister, ""
                    const-string v$accessibilityTextRegister, ""
                """
            )
        }

        // endregion


        // region Change Litho thread executor to 1 thread to fix layout issue in unpatched YouTube.

        LithoThreadExecutorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->getExecutorCorePoolSize(I)I
                move-result p1
                invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->getExecutorMaxThreads(I)I
                move-result p2
            """
        )

        // endregion


        // region A/B test of new Litho native code.

        // Turn off native code that handles litho component names.  If this feature is on then nearly
        // all litho components have a null name and identifier/path filtering is completely broken.
        //
        // Flag was removed in 20.05. It appears a new flag might be used instead (45660109L),
        // but if the flag is forced on then litho filtering still works correctly.
        if (is_19_25_or_greater && !is_20_05_or_greater) {
            LithoComponentNameUpbFeatureFlagFingerprint.method.returnLate(false)
        }

        // Turn off a feature flag that enables native code of protobuf parsing (Upb protobuf).
        LithoConverterBufferUpbFeatureFlagFingerprint.let {
            // 20.22 the flag is still enabled in one location, but what it does is not known.
            // Disable it anyway.
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                false
            )
        }

        // endregion
    }

    finalize {
        LithoFilterFingerprint.method.replaceInstruction(0, "const/16 v0, $filterCount")
    }
}