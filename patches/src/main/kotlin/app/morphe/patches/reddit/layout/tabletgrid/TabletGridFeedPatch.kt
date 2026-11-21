/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.tabletgrid

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/TabletGridFeedPatch;"

internal const val FEED_TYPE_CLASS = "Lcom/reddit/feeds/data/FeedType;"

@Suppress("unused")
val tabletGridFeedPatch = patch(
    name = "Tablet grid feed",
    description = "Adds an option to show feeds as a grid with 2 or 3 columns on tablets."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // Number of grid columns.
        CalculateCrossAxisCellSizesFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { p1, v$register }, $EXTENSION_CLASS->getGridColumns(II)I
                        move-result v$register
                    """
                )
            }
        }

        // Show the list feed with the grid feed composable that search results use.
        val gridFeed = SearchGridFeedFingerprint.instructionMatches[1].methodReference
        val gridParameters = gridFeed.parameterTypes.map { it.toString() }
        val footerField = SearchGridFeedFooterFingerprint.instructionMatches[1].fieldReference
        val paddingType = gridParameters[4]

        LatestFeedScreenFingerprint.instructionMatches[1].getMethodCalled().apply {
            val listParameters = parameterTypes.map { it.toString() }

            // Some app versions sort the list feed parameters by type,
            // so the list feed arguments are found by type and not by position.
            // Each argument used is the first of its type, and the first float is the top padding.
            fun listRegister(type: String) = "p${listParameters.indexOf(type)}"

            // The leading grid parameters are the same in all app versions.
            // The number of boolean flags and Compose int flags after them differ.
            // Padding is set to v4 before the other arguments.
            val setGridArguments = gridParameters.mapIndexedNotNull { index, type ->
                val register = "v$index"
                when (index) {
                    // Feed state, event sink, the first function and modifier.
                    0, 1, 2, 3 -> "move-object/from16 $register, ${listRegister(type)}"
                    4 -> null // Padding.
                    5 -> "const/4 $register, 0x0" // Item spacing.
                    6 -> "const/4 $register, 0x0" // Arrangement.
                    7 -> "sget-object $register, $footerField"
                    else -> when (type) {
                        "Z" -> "const/4 $register, 0x1"
                        "I" -> "const/4 $register, 0x0"
                        // Same object the list feed gets, such as the Compose composer.
                        else -> "move-object/from16 $register, ${listRegister(type)}"
                    }
                }
            }.joinToString("\n")

            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, ${listRegister(FEED_TYPE_CLASS)}
                    invoke-static { v0 }, $EXTENSION_CLASS->showGridFeed(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :list_feed

                    move/from16 v0, ${listRegister("F")}
                    invoke-static { v0 }, $EXTENSION_CLASS->getGridTopPadding(F)F
                    move-result v1
                    const/high16 v0, 0x41800000 # 16.0f
                    new-instance v4, $paddingType
                    invoke-direct { v4, v0, v1, v0, v0 }, $paddingType-><init>(FFFF)V
                    $setGridArguments
                    invoke-static/range { v0 .. v${gridParameters.size - 1} }, $gridFeed
                    return-void

                    :list_feed
                    nop
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
