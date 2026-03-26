/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.fix.backtoexitgesture.fixBackToExitGesturePatch
import app.morphe.patches.youtube.misc.fix.verticalscroll.fixVerticalScrollPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.patches.youtube.misc.litho.context.conversionContextPatch
import app.morphe.patches.youtube.misc.playservice.is_19_25_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_05_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.returnLate
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/components/LithoFilterPatch;"

internal const val EXTENSION_FILER_ARRAY_DESCRIPTOR = "[Lapp/morphe/extension/youtube/patches/components/Filter;"

// Registers used in extension helperMethod.
private const val REGISTER_FILTER_CLASS = 0
private const val REGISTER_FILTER_COUNT = 1
private const val REGISTER_FILTER_ARRAY = 2

private lateinit var helperMethodRef : WeakReference<MutableMethod>
private var addLithoFilterCount = 0

fun addLithoFilter(classDescriptor: String) {
    helperMethodRef.get()!!.addInstructions(
        0,
        """
            new-instance v$REGISTER_FILTER_CLASS, $classDescriptor
            invoke-direct { v$REGISTER_FILTER_CLASS }, $classDescriptor-><init>()V
            const/16 v$REGISTER_FILTER_COUNT, ${addLithoFilterCount++}
            aput-object v$REGISTER_FILTER_CLASS, v$REGISTER_FILTER_ARRAY, v$REGISTER_FILTER_COUNT
        """
    )
}

val lithoFilterPatch = bytecodePatch(
    description = "Hooks the method which parses the bytes into a ComponentContext to filter components.",
) {
    dependsOn(
        sharedExtensionPatch,
        conversionContextPatch,
        versionCheckPatch,
        fixBackToExitGesturePatch,
        fixVerticalScrollPatch,
    )

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
        LithoFilterFingerprint.let {
            it.method.apply {
                // Add a helper method to avoid finding multiple free registers.
                // This fixes an issue with extension compiled with Android Gradle Plugin 8.3.0+.
                val helperClass = definingClass
                val helperName = "patch_getFilterArray"
                val helperReturnType = EXTENSION_FILER_ARRAY_DESCRIPTOR
                val helperMethod = ImmutableMethod(
                    helperClass,
                    helperName,
                    listOf(),
                    helperReturnType,
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable()
                it.classDef.methods.add(helperMethod)
                helperMethodRef = WeakReference(helperMethod)

                val insertIndex = it.instructionMatches.first().index
                val insertRegister =
                    getInstruction<OneRegisterInstruction>(insertIndex).registerA

                addInstructions(
                    insertIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$helperName()$EXTENSION_FILER_ARRAY_DESCRIPTOR
                        move-result-object v$insertRegister
                    """
                )
            }
        }

        // region Pass the buffer into extension.

        if (!is_20_22_or_greater) {
            // Non-native buffer.
            ProtobufBufferReferenceFingerprint.method.addInstruction(
                0,
                "invoke-static { p2 }, $EXTENSION_CLASS_DESCRIPTOR->setProtoBuffer(Ljava/nio/ByteBuffer;)V",
            )
        }

        val protoBufferEncodeMethod = ProtobufBufferEncodeFingerprint.method

        // endregion


        // region Modify the create component method and
        // if the component is filtered then return an empty component.

        // Find class and methods to create an empty component.
        val builderMethodDescriptor = EmptyComponentFingerprint.method

        val emptyComponentField = classDefBy(builderMethodDescriptor.returnType).fields.single()

        // Find the method call that gets the value of 'buttonViewModel.accessibilityId'.
        val accessibilityIdMethod = AccessibilityIdFingerprint.instructionMatches.first()
            .instruction.getReference<MethodReference>()!!

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
        val accessibilityTextMethod = accessibilityTextFingerprint.instructionMatches.first()
            .instruction.getReference<MethodReference>()!!

        val componentCreateFingerprint = Fingerprint(
            returnType = "L",
            filters = listOf(
                opcode(Opcode.IF_EQZ),
                checkCast(
                    type = accessibilityIdMethod.definingClass,
                    location = MatchAfterWithin(5)
                ),
                opcode(Opcode.RETURN_OBJECT),
                string("Element missing correct type extension"),
                string("Element missing type")
            )
        )

        componentCreateFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches[2].index
                val buttonViewModelIndex = it.instructionMatches[1].index
                val nullCheckIndex = it.instructionMatches.first().index

                val buttonViewModelRegister =
                    getInstruction<OneRegisterInstruction>(buttonViewModelIndex).registerA
                val accessibilityIdIndex = buttonViewModelIndex + 2

                val registerProvider = getFreeRegisterProvider(
                    insertIndex, 3, buttonViewModelRegister
                )
                val contextRegister = registerProvider.getFreeRegister()
                val bufferRegister = registerProvider.getFreeRegister()
                val freeRegister = registerProvider.getFreeRegister()

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
                        move-object/from16 v$bufferRegister, p3

                        # Verify it's the expected subclass just in case.
                        instance-of v$freeRegister, v$bufferRegister, ${protoBufferEncodeMethod.definingClass}
                        if-eqz v$freeRegister, :empty_buffer

                        check-cast v$bufferRegister, ${protoBufferEncodeMethod.definingClass}
                        invoke-virtual { v$bufferRegister }, $protoBufferEncodeMethod
                        move-result-object v$bufferRegister
                        goto :hook

                        :empty_buffer
                        const/4 v$freeRegister, 0x0
                        new-array v$bufferRegister, v$freeRegister, [B

                        :hook
                        move-object/from16 v$contextRegister, p2
                        invoke-static { v$contextRegister, v$bufferRegister, v$accessibilityIdRegister, v$accessibilityTextRegister }, $EXTENSION_CLASS_DESCRIPTOR->isFiltered(${EXTENSION_CONTEXT_INTERFACE}[BLjava/lang/String;Ljava/lang/String;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :unfiltered
                        
                        # Return an empty component.
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

        // Turn off native code that handles litho component names. If this feature is on then nearly
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
        helperMethodRef.get()!!.apply {
            addInstruction(
                implementation!!.instructions.size,
                "return-object v$REGISTER_FILTER_ARRAY"
            )

            addInstructions(
                0,
                """
                    const/16 v$REGISTER_FILTER_COUNT, $addLithoFilterCount
                    new-array v$REGISTER_FILTER_ARRAY, v$REGISTER_FILTER_COUNT, $EXTENSION_FILER_ARRAY_DESCRIPTOR
                """
            )
        }
    }
}