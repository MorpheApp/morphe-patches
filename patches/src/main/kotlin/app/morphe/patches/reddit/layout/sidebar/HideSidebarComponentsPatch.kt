/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch;"

private const val EXTENSION_HEADER_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch$HeaderItemInterface;"

private const val EXTENSION_TITLE_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch$TitleItemInterface;"

private const val EXTENSION_DIVIDER_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideSidebarComponentsPatch$DividerItemInterface;"

@Suppress("unused")
val hideSidebarComponentsPatch = patch(
    name = "Hide sidebar components",
    description = "Adds options to hide the sidebar components."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommunityDrawerBuilderFingerprint.method.apply {
            val collectionParameter = parameterTypes.indexOf("Ljava/util/Collection;")
            addInstructions(
                0,
                """
                    invoke-static/range { p$collectionParameter .. p${collectionParameter + 1} }, $EXTENSION_CLASS->hideComponents(Ljava/util/Collection;$EXTENSION_HEADER_ITEM_INTERFACE)Ljava/util/Collection;
                    move-result-object p$collectionParameter
                """
            )
        }

        HeaderItemUiModelToStringFingerprint.let {
            it.classDef.apply {
                // Add interface and helper methods to allow extension code to call obfuscated methods.
                // Add methods to access obfuscated header item fields.
                val headerItemField = fields.single { field ->
                    field.type == HEADER_ITEM_CLASS
                }
                addPatchInterface(
                    EXTENSION_HEADER_ITEM_INTERFACE,
                    "patch_getItemName",
                    """
                        iget-object v0, p0, $headerItemField
                        if-nez v0, :get_name
                        const-string v0, ""
                        return-object v0
                        :get_name
                        invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    """
                )
            }
        }

        // Add an interface to the items without a header (Popular, Latest, Discover communities,
        // Start a community) to allow extension code to get the title resource id.
        listOf(
            CtaItemUiModelToStringFingerprint,
            GenericItemUiModelToStringFingerprint
        ).forEach { fingerprint ->
            val titleResIdField = fingerprint.instructionMatches.last()
                .getInstruction<ReferenceInstruction>().getReference<FieldReference>()!!

            fingerprint.classDef.addPatchInterface(
                EXTENSION_TITLE_ITEM_INTERFACE,
                "patch_getTitleResId",
                """
                    iget v0, p0, $titleResIdField
                    return v0
                """
            )
        }

        // Mark the divider items, so extension code can remove the dividers
        // that are left behind by hidden items.
        DividerItemUiModelToStringFingerprint.classDef.addPatchInterface(
            EXTENSION_DIVIDER_ITEM_INTERFACE,
            "patch_isDivider",
            """
                const/4 v0, 0x1
                return v0
            """
        )

        CommunityDrawerBindListFingerprint.let {
            it.method.apply {
                // The presenter finds a clicked item by its adapter position in this list,
                // so the filtered list is written back to the field before it is read.
                val index = it.instructionMatches.first().index
                val presenterRegister = getInstruction<TwoRegisterInstruction>(index).registerB
                val listField = getInstruction<ReferenceInstruction>(index).reference
                val freeRegister = findFreeRegister(index, presenterRegister)

                addInstructions(
                    index,
                    """
                        iget-object v$freeRegister, v$presenterRegister, $listField
                        invoke-static/range { v$freeRegister .. v$freeRegister }, $EXTENSION_CLASS->removeExtraDividers(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$freeRegister
                        iput-object v$freeRegister, v$presenterRegister, $listField
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
