package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.is_2025_45_or_greater
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/SidebarComponentsPatch;"

@Suppress("unused")
val sidebarComponentsPatch = bytecodePatch(
    name = "Hide sidebar components",
    description = "Adds options to hide the sidebar components."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        if (is_2025_45_or_greater) {
            val helperMethodName = "getHeaderItemName"

            headerItemUiModelToStringFingerprint.match().let {
                it.method.apply {
                    val headerItemField = findFieldFromToString(", type=")

                    it.classDef.methods.add(
                        ImmutableMethod(
                            definingClass,
                            helperMethodName,
                            listOf(),
                            "Ljava/lang/String;",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null, null,
                            MutableMethodImplementation(2)
                        ).toMutable().apply {
                            addInstructionsWithLabels(
                                0,
                                """
                                    iget-object v0, p0, $headerItemField
                                    if-nez v0, :name
                                    const-string v0, ""
                                    return-object v0
                                    :name
                                    invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
                                    move-result-object v0
                                    return-object v0
                                """
                            )
                        }
                    )
                }
            }

            val headerItemUiModelClass =
                headerItemUiModelToStringFingerprint.method.definingClass

            sidebarComponentsPatchFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                        check-cast p0, $headerItemUiModelClass
                        invoke-virtual { p0 }, $headerItemUiModelClass->$helperMethodName()Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    """
                )
            }

            val isShelfBuilderMethod: Method.() -> Boolean = {
                parameterTypes.size > 4 &&
                        accessFlags == AccessFlags.PUBLIC.value or AccessFlags.STATIC.value &&
                        returnType == "V" &&
                        parameterTypes[1].equals("Ljava/util/List;") &&
                        parameterTypes[2].equals("Ljava/util/Collection;") &&
                        parameterTypes[3].equals(headerItemUiModelClass)
            }

            var shelfBuilderMethodFound = false
            classDefForEach handler@{ classDef ->
                classDef.methods.forEach { method ->
                    if (method.isShelfBuilderMethod()) {
                        shelfBuilderMethodFound = true
                        mutableClassDefBy(classDef)
                            .findMutableMethodOf(method)
                            .addInstructions(
                                0,
                                """
                                    invoke-static/range { p2 .. p3 }, $EXTENSION_CLASS_DESCRIPTOR->hideComponents(Ljava/util/Collection;Ljava/lang/Object;)Ljava/util/Collection;
                                    move-result-object p2
                                """
                            )

                        return@handler
                    }
                }
            }

            if (!shelfBuilderMethodFound) {
                throw PatchException("Unable to find shelf builder method")
            }
        } else {
            val communityDrawerPresenterMethod =
                communityDrawerPresenterFingerprint.match(
                    communityDrawerPresenterConstructorFingerprint.classDef
                ).method

            fun getDrawerField(
                fieldName: String,
                isRedditPro: Boolean
            ): FieldReference {
                val targetMethod = if (isRedditPro) {
                    redditProLoaderFingerprint.method
                } else {
                    communityDrawerPresenterConstructorFingerprint.method
                }

                targetMethod.apply {
                    val headerItemIndex =
                        indexOfHeaderItemInstruction(this, fieldName)
                    val fieldIndex =
                        indexOfFirstInstructionOrThrow(headerItemIndex, Opcode.IPUT_OBJECT)

                    return getInstruction<ReferenceInstruction>(fieldIndex).reference
                            as FieldReference
                }
            }

            fun hideShelf(
                fieldName: String,
                methodNamePrefix: String,
                isRedditPro: Boolean
            ) {
                val fieldReference = getDrawerField(fieldName, isRedditPro)

                communityDrawerPresenterMethod.apply {
                    val fieldIndex =
                        indexOfFirstInstructionOrThrow {
                            opcode == Opcode.IGET_OBJECT &&
                                    getReference<FieldReference>() == fieldReference
                        }

                    val collectionIndex =
                        indexOfKotlinCollectionInstruction(this, fieldIndex)
                    val collectionInstruction =
                        getInstruction<FiveRegisterInstruction>(collectionIndex)

                    val iterableRegister = collectionInstruction.registerC
                    val collectionRegister = collectionInstruction.registerD

                    addInstructions(
                        collectionIndex,
                        """
                            invoke-static { v$iterableRegister }, $EXTENSION_CLASS_DESCRIPTOR->${methodNamePrefix}Divider(Ljava/lang/Iterable;)Ljava/lang/Iterable;
                            move-result-object v$iterableRegister
                            invoke-static { v$collectionRegister }, $EXTENSION_CLASS_DESCRIPTOR->${methodNamePrefix}Shelf(Ljava/util/Collection;)Ljava/util/Collection;
                            move-result-object v$collectionRegister
                        """
                    )
                }
            }

            val hooks = mutableListOf(
                Triple(
                    "RECENTLY_VISITED",
                    "hideRecentlyVisited",
                    false
                ),
                Triple(
                    "GAMES_ON_REDDIT",
                    "hideGamesOnReddit",
                    false
                ),
                Triple(
                    "REDDIT_PRO",
                    "hideRedditPro",
                    true
                )
            )

            hooks.forEach { (fieldName, methodNamePrefix, isRedditPro) ->
                hideShelf(fieldName, methodNamePrefix, isRedditPro)
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}