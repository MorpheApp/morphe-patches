/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.compose

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.layout.glassnav.BottomNavBarFingerprint
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch

private const val CONTENT_DRAW_SCOPE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$ContentDrawScope;"
private const val COMPOSE_CANVAS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$ComposeCanvas;"
internal const val MODIFIER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$Modifier;"
private const val MUTABLE_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$MutableState;"
private const val MUTABLE_STATE_CLASS = "Landroidx/compose/runtime/ParcelableSnapshotMutableState;"
internal const val WINDOW_INSETS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$WindowInsets;"

/**
 * Obfuscated type of Modifier.
 */
internal lateinit var modifierType: String
    private set

/**
 * Adds access to Compose classes. Does nothing if Reddit is lower than 2026.24.0.
 */
internal val composePatch = patch {
    dependsOn(sharedExtensionPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) return@execute

        val drawWithContentMethod = BottomNavBarFingerprint.instructionMatches[0].methodReference
        modifierType = drawWithContentMethod.parameterTypes[0].toString()

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
                "patch_offset",
                """
                    invoke-static/range { p0 .. p2 }, ${OffsetFingerprint.method}
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

        if (is_2026_45_0_or_greater) {
            // Each RowScope and ColumnScope implementation creates the same weight element.
            val weightInit = WeightElementFingerprint.matchAll().first().instructionMatches[1].methodReference

            NewWeightModifierFingerprint.method.addFreeRegisters(1).first.addInstructions(
                0,
                """
                    new-instance v0, ${weightInit.definingClass}
                    invoke-direct { v0, p0, p1 }, $weightInit
                    return-object v0
                """
            )

            val graphicsLayerMethod = GraphicsLayerFingerprint.method
            val shapeType = graphicsLayerMethod.parameterTypes[6]

            // 0x7e7ff marks every parameter as default except the shape and clip.
            mutableClassDefBy(modifierType).addPatchInterface(
                MODIFIER_INTERFACE,
                "patch_clip",
                """
                    move-object v0, p0
                    const/4 v1, 0x0
                    const/4 v2, 0x0
                    const/4 v3, 0x0
                    const/4 v4, 0x0
                    const/4 v5, 0x0
                    move-object v6, p1
                    check-cast v6, $shapeType
                    const/4 v7, 0x1
                    const/4 v8, 0x0
                    const v9, 0x7e7ff
                    invoke-static/range { v0 .. v9 }, $graphicsLayerMethod
                    move-result-object v0
                    return-object v0
                """
            )
        }

        // Used by 2026.47.0+ code only. 2026.24.0 has no matching mutableStateOf.
        if (is_2026_47_0_or_greater) {
            NewMutableStateFingerprint.method.addFreeRegisters(1).first.addInstructions(
                0,
                """
                    invoke-static { p0 }, ${MutableStateOfFingerprint.method}
                    move-result-object v0
                    return-object v0
                """
            )

            mutableClassDefBy(MUTABLE_STATE_CLASS).apply {
                addPatchInterface(
                    MUTABLE_STATE_INTERFACE,
                    "patch_getValue",
                    """
                        invoke-virtual { p0 }, $MUTABLE_STATE_CLASS->getValue()Ljava/lang/Object;
                        move-result-object v0
                        return-object v0
                    """
                )
                addPatchInterface(
                    MUTABLE_STATE_INTERFACE,
                    "patch_setValue",
                    """
                        invoke-virtual { p0, p1 }, $MUTABLE_STATE_CLASS->setValue(Ljava/lang/Object;)V
                        return-void
                    """
                )
            }
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
                DrawRoundRectFingerprint.instructionMatches[0].methodReference
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
    }
}
