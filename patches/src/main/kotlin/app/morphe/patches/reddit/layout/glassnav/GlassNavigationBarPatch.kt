/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch;"
private const val CONTENT_DRAW_SCOPE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$ContentDrawScope;"
private const val COMPOSE_CANVAS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$ComposeCanvas;"
internal const val MODIFIER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$Modifier;"
private const val NEUTRAL_COLORS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$NeutralColors;"

@Suppress("unused")
val glassNavigationBarPatch = patch(
    name = "Glass navigation bar",
    description = "Adds an option to show the bottom navigation bar, or the side rail on tablets, " +
        "as a floating translucent capsule. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Glass navigation bar' requires Reddit 2026.24.0+"
            )
        }

        val backdropFingerprint = if (is_2026_47_0_or_greater) {
            BottomNavBackdropFingerprint
        } else {
            BottomNavBackdropLegacyFingerprint
        }

        // region Access to Compose and theme classes.

        val drawWithContentMethod = BottomNavBarFingerprint.instructionMatches[0].methodReference
        val modifierType = drawWithContentMethod.parameterTypes[0].toString()

        mutableClassDefBy(modifierType).apply {
            addPatchInterface(
                MODIFIER_INTERFACE,
                "patch_drawWithContent",
                """
                    invoke-static { p0, p1 }, $drawWithContentMethod
                    move-result-object v0
                    return-object v0
                """
            )
            addPatchInterface(
                MODIFIER_INTERFACE,
                "patch_padding",
                """
                    invoke-static/range { p0 .. p4 }, ${PaddingFingerprint.method}
                    move-result-object v0
                    return-object v0
                """
            )
        }

        val nativeCanvasField = LottieAnimationDrawFingerprint.instructionMatches[4].fieldReference

        mutableClassDefBy(nativeCanvasField.definingClass).apply {
            addPatchInterface(COMPOSE_CANVAS_INTERFACE, "patch_getNativeCanvas", nativeCanvasField)
            addPatchInterface(COMPOSE_CANVAS_INTERFACE, "patch_setNativeCanvas", nativeCanvasField)
        }

        DrawContentFingerprint.classDef.apply {
            addPatchInterface(
                CONTENT_DRAW_SCOPE_INTERFACE,
                "patch_getCanvas",
                """
                    invoke-interface { p0 }, ${LottieAnimationDrawFingerprint.instructionMatches[0].methodReference}
                    move-result-object v0
                    invoke-virtual { v0 }, ${LottieAnimationDrawFingerprint.instructionMatches[2].methodReference}
                    move-result-object v0
                    check-cast v0, $COMPOSE_CANVAS_INTERFACE
                    return-object v0
                """
            )
            addPatchInterface(
                CONTENT_DRAW_SCOPE_INTERFACE,
                "patch_getSize",
                """
                    invoke-interface { p0 }, ${DrawRoundRectFingerprint.instructionMatches[0].methodReference}
                    move-result-wide v0
                    return-wide v0
                """
            )
            addPatchInterface(
                CONTENT_DRAW_SCOPE_INTERFACE,
                "patch_drawContent",
                DrawContentFingerprint.method
            )
            // 0xf0 mask uses the default style, alpha, color filter and blend mode.
            addPatchInterface(
                CONTENT_DRAW_SCOPE_INTERFACE,
                "patch_drawRoundRect",
                """
                    move-object/from16 v0, p0
                    move-wide/from16 v1, p1
                    move-wide/from16 v3, p3
                    move-wide/from16 v5, p5
                    move-wide/from16 v7, p7
                    const/4 v9, 0x0
                    const/4 v10, 0x0
                    const/16 v11, 0xf0
                    invoke-static/range { v0 .. v11 }, ${DrawRoundRectFingerprint.method}
                    return-void
                """
            )
        }

        val backgroundMethod = backdropFingerprint.instructionMatches[2].methodReference
        val railBackgroundMethod = RailBackgroundFingerprint.instructionMatches[3].methodReference

        mutableClassDefBy(backgroundMethod.definingClass).apply {
            addPatchInterface(NEUTRAL_COLORS_INTERFACE, "patch_getBackground", backgroundMethod)
            addPatchInterface(NEUTRAL_COLORS_INTERFACE, "patch_getRailBackground", railBackgroundMethod)
        }

        // endregion

        // region Side rail.

        SideNavRailContentFingerprint.let {
            it.method.apply {
                val modifierIndex = it.instructionMatches[3].index
                val modifierRegister = it.instructionMatches[3].instruction.registersUsed[1]

                addInstructions(
                    modifierIndex,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getRailModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                    """
                )

                val heightIndex = it.instructionMatches[1].index
                val heightRegister = it.instructionMatches[1].instruction.registersUsed[0]

                addInstruction(
                    heightIndex,
                    "invoke-static/range { v$heightRegister .. v$heightRegister }, " +
                        "$EXTENSION_CLASS->setRailHeight(F)V"
                )
            }
        }

        RailBackgroundFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[3].index
                val register = it.instructionMatches[3].instruction.registersUsed[0]

                replaceInstruction(
                    index,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_CLASS->getRailBackground($NEUTRAL_COLORS_INTERFACE)J"
                )
            }
        }

        // endregion

        // region Bottom navigation bar.

        backdropFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[3].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getBarColor(J)J
                        move-result-wide v$register
                    """
                )
            }
        }

        BottomNavBarFingerprint.let {
            it.method.apply {
                val surfaceIndex = it.instructionMatches[2].index
                val surfaceRegisters = it.instructionMatches[2].instruction.registersUsed
                val modifierRegister = surfaceRegisters[0]
                val elevationRegister = surfaceRegisters[2]
                val colorRegister = surfaceRegisters[3]

                addInstructions(
                    surfaceIndex,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getBarModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                        invoke-static/range { v$elevationRegister .. v$elevationRegister }, $EXTENSION_CLASS->getBarElevation(F)F
                        move-result v$elevationRegister
                        invoke-static/range { v$colorRegister .. v${colorRegister + 1} }, $EXTENSION_CLASS->getBarColor(J)J
                        move-result-wide v$colorRegister
                    """
                )

                val drawIndex = it.instructionMatches[0].index
                val drawRegister = it.instructionMatches[0].instruction.registersUsed[1]

                addInstructions(
                    drawIndex,
                    """
                        invoke-static/range { v$drawRegister .. v$drawRegister }, $EXTENSION_CLASS->getBarDraw(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
                        move-result-object v$drawRegister
                    """
                )
            }
        }

        BottomNavStackHostFingerprint.matchAll().forEach {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val modifierRegister = it.instructionMatches[1].instruction.registersUsed[1]

                addInstructions(
                    index,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getContentModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                    """
                )
            }
        }

        if (is_2026_47_0_or_greater) {
            NavScreenContentStateConstructorFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p2 }, $EXTENSION_CLASS->padContentForBottomNav(Z)Z
                    move-result p2
                """
            )
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
