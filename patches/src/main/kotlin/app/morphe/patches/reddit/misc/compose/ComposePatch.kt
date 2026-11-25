/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.compose

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.layout.glassnav.BottomNavBarFingerprint
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch

private const val CONTENT_DRAW_SCOPE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$ContentDrawScope;"
private const val COMPOSE_CANVAS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$ComposeCanvas;"
internal const val MODIFIER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/utils/ComposeUtils$Modifier;"
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
    }
}
