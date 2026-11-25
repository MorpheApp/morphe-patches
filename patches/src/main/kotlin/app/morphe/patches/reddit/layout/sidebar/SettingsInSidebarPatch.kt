/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SettingsInSidebarPatch;"

private const val EXTENSION_SIDEBAR_PRESENTER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SettingsInSidebarPatch$SidebarPresenterInterface;"

@Suppress("unused")
val settingsInSidebarPatch = patch(
    name = "Settings in sidebar",
    description = "Adds an option to show the Morphe settings at the top of the sidebar."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        val ctaItemType = CtaItemUiModelToStringFingerprint.originalClassDef.type

        // Implement the extension placeholder that creates a sidebar row.
        mutableClassDefBy(EXTENSION_CLASS).apply {
            val placeholder = methods.single { it.name == "createSettingsRow" }
            methods.remove(placeholder)

            addBridge(
                placeholder.name,
                listOf("I", "I", "J"), // Title resource id, icon resource id, unique id.
                "Ljava/lang/Object;",
                10,
                """
                    # The parameters must be in consecutive registers.
                    new-instance v0, $ctaItemType
                    const/4 v1, 0x0 # Do not show a badge.
                    move v2, p0
                    move v3, p1
                    move-wide v4, p2
                    invoke-direct/range { v0 .. v5 }, $ctaItemType-><init>(ZIIJ)V
                    return-object v0
                """,
                placeholder.accessFlags
            )
        }

        // The presenter finds a clicked item by its adapter position in the list field,
        // so the row is added before the list is written to the field.
        CommunityDrawerBuildListFingerprint.let {
            val index = it.instructionMatches.first().index
            it.method.apply {
                val listRegister = getInstruction(index).registersUsed[0]

                addInstructions(
                    index,
                    """
                        invoke-static/range { v$listRegister .. v$listRegister }, $EXTENSION_CLASS->addSettingsRow(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$listRegister
                    """
                )
            }
        }

        CommunityDrawerItemClickFingerprint(ctaItemType).let {
            val screenField = it.instructionMatches[0].fieldReference
            val closeSidebarMethod = it.instructionMatches[2].methodReference

            it.classDef.addPatchInterface(
                EXTENSION_SIDEBAR_PRESENTER_INTERFACE,
                "patch_closeSidebar",
                """
                    iget-object v0, p0, $screenField
                    invoke-virtual { v0 }, $closeSidebarMethod
                    return-void
                """
            )

            it.method.apply {
                val checkCastIndex = it.instructionMatches[1].index
                val itemRegister = getInstruction(checkCastIndex).registersUsed[0]
                val insertIndex = checkCastIndex + 1
                val freeRegister = findFreeRegister(insertIndex, itemRegister)

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static/range { v$itemRegister .. v$itemRegister }, $EXTENSION_CLASS->isSettingsRow(Ljava/lang/Object;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->openSettings($EXTENSION_SIDEBAR_PRESENTER_INTERFACE)V
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
