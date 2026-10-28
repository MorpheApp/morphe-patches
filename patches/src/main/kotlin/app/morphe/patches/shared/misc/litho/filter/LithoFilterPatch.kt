/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.shared.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.litho.context.ConversionContextToStringFingerprint
import app.morphe.patches.shared.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.patches.shared.misc.litho.context.conversionContextClassDef
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/components/LithoFilterPatch;"

internal const val EXTENSION_FILTER = "[Lapp/morphe/extension/shared/patches/components/Filter;"

private const val EXTENSION_LIST_ITEM_DECORATOR_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/components/LithoFilterPatch$ListItemDecoratorInterface;"

private const val EXTENSION_LIST_SECTION_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/components/LithoFilterPatch$ListSectionInterface;"

private const val COMMAND_EVENT_DATA_DECORATORS_PROPERTY = ", globalCommandEventDataDecoratorsInternal="

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

/**
 * Shared Litho component filter factory used by both YouTube and YT Music.
 *
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
 *
 * @param overrideUpbFeatureFlag Whether to override the A/B feature flag that enables Upb-native
 *                               protobuf parsing (only present on older YouTube; absent in Music).
 * @param removeFilteredListItems Whether to remove the items of the lists (such as the feed) whose
 *                                whole element is filtered. Otherwise the grids, such as the feed
 *                                of the tablet layout, show an empty cell in their place.
 * @param block Callback to add app-specific dependencies (sharedExtensionPatch, conversionContextPatch,
 *              versionCheckPatch, and any app-specific fixes).
 */
internal fun sharedLithoFilterPatch(
    overrideUpbFeatureFlag: () -> Boolean,
    removeFilteredListItems: Boolean,
    block: BytecodePatchBuilder.() -> Unit,
): BytecodePatch = bytecodePatch(
    description = "Hooks the method which parses the bytes into a ComponentContext to filter components."
) {
    block()

    execute {
        // Remove dummy filter from extenion static field
        // and add the filters included during patching.
        LithoFilterFingerprint.let {
            it.method.apply {
                // Add a helper method to avoid finding multiple free registers.
                // This fixes an issue with extension compiled with Android Gradle Plugin 8.3.0+.
                val helperClass = definingClass
                val helperName = "patch_getFilterArray"
                val helperReturnType = EXTENSION_FILTER
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
                        invoke-static {}, $EXTENSION_CLASS->$helperName()$EXTENSION_FILTER
                        move-result-object v$insertRegister
                    """
                )
            }
        }

        // region Modify the create component method and
        // if the component is filtered then return an empty component.

        val protoBufferEncodeMethod = ProtobufBufferEncodeFingerprint.method

        // Find class and methods to create an empty component.
        val builderMethodDescriptor = EmptyComponentFingerprint.method

        val emptyComponentField = classDefBy(builderMethodDescriptor.returnType).fields.single()

        // Find the method call that gets the value of 'buttonViewModel.accessibilityId'.
        val accessibilityIdMethod = AccessibilityIdFingerprint.instructionMatches.first()
            .instruction.getReference<MethodReference>()!!

        // There's a method in the same class that gets the value of 'buttonViewModel.accessibilityText'.
        // As this class is abstract, we need to find another method that uses a method call.
        // Find the method call that gets the value of 'buttonViewModel.accessibilityText'.
        val accessibilityTextMethod = Fingerprint(
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
        ).instructionMatches.first().instruction.getReference<MethodReference>()!!

        Fingerprint(
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
        ).let {
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
                        invoke-static { v$contextRegister, v$bufferRegister, v$accessibilityIdRegister, v$accessibilityTextRegister }, $EXTENSION_CLASS->isFiltered(${EXTENSION_CONTEXT_INTERFACE}[BLjava/lang/String;Ljava/lang/String;)Z
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


        // region Remove the items of the lists whose whole element is filtered.

        if (removeFilteredListItems) {
            // Give the extension the decorators of the conversion context.
            val decoratorsMethod = commandEventDataDecoratorsFingerprint(
                ConversionContextToStringFingerprint.method
                    .findFieldFromToString(COMMAND_EVENT_DATA_DECORATORS_PROPERTY)
            ).method

            conversionContextClassDef.methods.add(
                ImmutableMethod(
                    conversionContextClassDef.type,
                    "patch_getCommandEventDataDecorators",
                    listOf(),
                    "Ljava/util/List;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-virtual { p0 }, $decoratorsMethod
                            move-result-object v0
                            return-object v0
                        """
                    )
                }
            )

            // Give the extension the item that the decorator holds.
            CommandEventDataDecoratorFingerprint.let {
                val dataType = it.instructionMatches.first()
                    .instruction.getReference<MethodReference>()!!.definingClass
                val itemField = classDefBy(dataType).fields.single { field ->
                    field.type == "Ljava/lang/Object;"
                }

                mutableClassDefBy(
                    it.instructionMatches.last().instruction.getReference<MethodReference>()!!.definingClass
                ).apply {
                    val dataField = fields.single { field -> field.type == dataType }

                    interfaces.add(EXTENSION_LIST_ITEM_DECORATOR_INTERFACE)

                    methods.add(
                        ImmutableMethod(
                            type,
                            "patch_getListItem",
                            listOf(),
                            "Ljava/lang/Object;",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(2),
                        ).toMutable().apply {
                            addInstructionsWithLabels(
                                0,
                                """
                                    iget-object v0, p0, $dataField
                                    if-eqz v0, :no_data
                                    iget-object v0, v0, $itemField
                                    :no_data
                                    return-object v0
                                """
                            )
                        }
                    )
                }
            }

            // Keep the observable lists, to remove the items from them.
            // Some grids, such as the feed, have a list for each item.
            ObservableListConstructorFingerprint.method.apply {
                addInstruction(
                    implementation!!.instructions.lastIndex,
                    "invoke-static { p0 }, $EXTENSION_CLASS->addObservableList(Ljava/util/List;)V"
                )
            }

            // Keep the sections of the lists, to remove the items from them. Some grids, such as
            // the related videos, group the items of a section into rows, which the section lays
            // out again when an item is removed.
            ListSectionRemoveItemFingerprint.let {
                val itemsField = it.instructionMatches[2].getFieldAccessed()
                val removeItemMethod = it.method

                // Removing an item does not lay out the rows again when the section is no longer
                // a grid, such as when a single item is left, and the old rows that are views of
                // the items would throw on the next access.
                val (isGridMethod, layOutRowsMethod, updateSectionMethod) =
                    ListSectionUpdateRowsFingerprint.instructionMatches.let { matches ->
                        listOf(0, 3, 4).map { index ->
                            matches[index].instruction.getReference<MethodReference>()!!
                        }
                    }

                it.classDef.apply {
                    interfaces.add(EXTENSION_LIST_SECTION_INTERFACE)

                    methods.add(
                        ImmutableMethod(
                            type,
                            "patch_removeListItem",
                            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, "item")),
                            "Z",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(4),
                        ).toMutable().apply {
                            addInstructionsWithLabels(
                                0,
                                """
                                    iget-object v0, p0, $itemsField
                                    invoke-interface { v0, p1 }, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                                    move-result v0
                                    if-eqz v0, :done
                                    invoke-direct { p0 }, $isGridMethod
                                    move-result v1
                                    invoke-virtual { p0, p1 }, $removeItemMethod
                                    if-eqz v1, :done
                                    invoke-direct { p0 }, $isGridMethod
                                    move-result v1
                                    if-nez v1, :done
                                    iget-object v1, p0, $itemsField
                                    invoke-interface { v1 }, Ljava/util/List;->isEmpty()Z
                                    move-result v1
                                    if-nez v1, :done
                                    invoke-virtual { p0 }, $layOutRowsMethod
                                    invoke-virtual { p0 }, $updateSectionMethod
                                    :done
                                    return v0
                                """
                            )
                        }
                    )

                    // The released sections stop receiving the removed items.
                    ListSectionReleaseFingerprint.method.addInstruction(
                        0,
                        "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->" +
                                "removeListSection($EXTENSION_LIST_SECTION_INTERFACE)V"
                    )

                    // Only the constructors that call the constructor of the superclass,
                    // the others call those.
                    methods.filter { method -> MethodUtil.isConstructor(method) }.forEach { constructor ->
                        val superConstructorIndex = constructor.indexOfFirstInstruction {
                            opcode == Opcode.INVOKE_DIRECT && getReference<MethodReference>()?.let { reference ->
                                reference.name == "<init>" && reference.definingClass == superclass
                            } == true
                        }
                        if (superConstructorIndex < 0) return@forEach

                        // The instance the superclass was initialized with.
                        val sectionRegister = constructor
                            .getInstruction<FiveRegisterInstruction>(superConstructorIndex).registerC

                        constructor.addInstruction(
                            superConstructorIndex + 1,
                            "invoke-static/range { v$sectionRegister .. v$sectionRegister }, $EXTENSION_CLASS->" +
                                    "addListSection($EXTENSION_LIST_SECTION_INTERFACE)V"
                        )
                    }
                }
            }
        }

        // endregion


        // region Change Litho thread executor to 1 thread to fix layout issue in unpatched YouTube.

        LithoThreadExecutorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getExecutorCorePoolSize(I)I
                move-result p1
                invoke-static { p2 }, $EXTENSION_CLASS->getExecutorMaxThreads(I)I
                move-result p2
            """
        )

        // endregion


        // region A/B test of new Litho native code.

        // Turn off a feature flag that enables native code of protobuf parsing (Upb protobuf).
        if (overrideUpbFeatureFlag()) {
            LithoConverterBufferUpbFeatureFlagFingerprint.let {
                // 20.22 the flag is still enabled in one location, but what it does is not known.
                // Disable it anyway. Flag was removed in 21.15+
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
            }
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
                    new-array v$REGISTER_FILTER_ARRAY, v$REGISTER_FILTER_COUNT, $EXTENSION_FILTER
                """
            )
        }
    }
}
