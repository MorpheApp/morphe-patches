/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.statusbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.compose.LimitInsetsGetTopFingerprint
import app.morphe.patches.reddit.misc.compose.MODIFIER_INTERFACE
import app.morphe.patches.reddit.misc.compose.WINDOW_INSETS_INTERFACE
import app.morphe.patches.reddit.misc.compose.composePatch
import app.morphe.patches.reddit.misc.compose.modifierType
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_43_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SeeThroughStatusBarPatch;"

@Suppress("unused")
val seeThroughStatusBarPatch = patch(
    name = "See-through status bar",
    description = "Adds an option to let the home feed scroll up under the status bar and fade into it. " +
        "This patch works with Reddit 2026.43.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, composePatch)

    execute {
        if (!is_2026_43_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'See-through status bar' requires Reddit 2026.43.0+"
            )
        }

        val getTopMethod = LimitInsetsGetTopFingerprint.instructionMatches[1].methodReference

        // Window insets that come from the system are in pixels and do not use the density parameter.
        mutableClassDefBy(getTopMethod.definingClass).addPatchInterface(
            WINDOW_INSETS_INTERFACE,
            "patch_getTop",
            """
                const/4 v0, 0x0
                invoke-interface { p0, v0 }, $getTopMethod
                move-result v0
                return v0
            """
        )

        HomeScaffoldFingerprint.let {
            mutableClassDefBy(modifierType).addPatchInterface(
                MODIFIER_INTERFACE,
                "patch_windowInsetsPadding",
                """
                    check-cast p1, ${it.instructionMatches[6].methodReference.parameterTypes[1]}
                    invoke-static { p0, p1 }, ${it.instructionMatches[6].methodReference}
                    move-result-object v0
                    return-object v0
                """
            )

            it.method.apply {
                // Content of the app bar, which fades out when the app bar hides.
                val appBarIndex = it.instructionMatches[10].index
                val appBarRegister = getInstruction<OneRegisterInstruction>(appBarIndex).registerA

                // Receiver modifier going into the app bar graphicsLayer, outside the layer's own node.
                val appBarLayerIndex = it.instructionMatches[9].index
                val appBarLayerRegister = it.instructionMatches[9].instruction.registersUsed[0]

                // Background of the app bar and the status bar.
                val colorIndex = it.instructionMatches[8].index
                val colorRegister = it.instructionMatches[8].instruction.registersUsed[1]

                // Feed.
                val feedIndex = it.instructionMatches[6].index
                val (modifierRegister, insetsRegister) = it.instructionMatches[6].instruction.registersUsed
                val feedRegister = getInstruction<OneRegisterInstruction>(feedIndex + 1).registerA

                // Phone or tablet layout.
                val sideLayoutIndex = it.instructionMatches[1].index
                val sideLayoutRegister = getInstruction<OneRegisterInstruction>(sideLayoutIndex).registerA

                addInstructions(
                    appBarIndex + 1,
                    """
                        invoke-static/range { v$appBarRegister .. v$appBarRegister }, $EXTENSION_CLASS->getAppBarModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$appBarRegister
                        check-cast v$appBarRegister, $modifierType
                    """
                )

                addInstructionsAtControlFlowLabel(
                    appBarLayerIndex,
                    """
                        invoke-static/range { v$appBarLayerRegister .. v$appBarLayerRegister }, $EXTENSION_CLASS->getAppBarLayerModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$appBarLayerRegister
                        check-cast v$appBarLayerRegister, $modifierType
                    """
                )

                addInstructionsAtControlFlowLabel(
                    colorIndex,
                    """
                        invoke-static/range { v$colorRegister .. v${colorRegister + 1} }, $EXTENSION_CLASS->getAppBarColor(J)J
                        move-result-wide v$colorRegister
                    """
                )

                addInstruction(
                    feedIndex + 2,
                    "check-cast v$feedRegister, $modifierType"
                )
                replaceInstruction(
                    feedIndex,
                    "invoke-static { v$modifierRegister, v$insetsRegister }, $EXTENSION_CLASS->" +
                        "getFeedModifier($MODIFIER_INTERFACE$WINDOW_INSETS_INTERFACE)Ljava/lang/Object;"
                )

                addInstruction(
                    sideLayoutIndex + 1,
                    "invoke-static/range { v$sideLayoutRegister .. v$sideLayoutRegister }, " +
                        "$EXTENSION_CLASS->setSideLayout(Z)V"
                )
            }
        }

        HomeTopBarHeightFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->getTopBarHeight(F)F
                        move-result v$register
                    """
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
