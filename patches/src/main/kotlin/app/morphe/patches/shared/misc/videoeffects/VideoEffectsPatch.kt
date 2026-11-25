/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.videoeffects

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.fiveRegisters
import app.morphe.util.insertLiteralOverride
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.util.logging.Logger

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/VideoEffectsPatch;"

private const val EXTENSION_EGL_HELPER_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/VideoEffectsPatch$EglHelper;"

private const val EXTENSION_VIDEO_RENDERER_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/VideoEffectsPatch$VideoRenderer;"

/**
 * @param targetCompatible If the app version has the video renderer with the passthrough shader.
 * @param hookVideoId Adds a call to a method with the id of the video that is playing.
 */
internal fun videoEffectsPatch(
    description: String,
    block: PatchDefinition.() -> Unit,
    targetCompatible: PatchDefinition.() -> Boolean = { true },
    preferenceScreen: BasePreferenceScreen.Screen,
    hookVideoId: (methodDescriptor: String) -> Unit,
    executeBlock: PatchDefinition.() -> Unit = {},
) = patch(
    name = "Video effects",
    description = description
) {
    block()

    execute {
        if (!targetCompatible()) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Video effects' does not support older legacy app targets."
            )
        }

        preferenceScreen.addPreferences(
            ListPreference(
                key = "morphe_video_effect",
                tag = "app.morphe.extension.shared.settings.preference.VideoEffectListPreference",
                // The effects are user data, and the preference adds them.
                entriesKey = null,
                entryValuesKey = null
            ),
            NonInteractivePreference(
                key = "morphe_video_effects_custom",
                tag = "app.morphe.extension.shared.settings.preference.VideoEffectCustomEffectsPreference",
                selectable = true
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        hookVideoId("$EXTENSION_CLASS->setVideoId(Ljava/lang/String;)V")

        GlVideoRendererFeatureFlagFingerprint.matchAll().forEach { match ->
            match.method.insertLiteralOverride(
                match.instructionMatches.first().index,
                "$EXTENSION_CLASS->useGlVideoRenderer(Z)Z"
            )
        }

        // Match the constructor before the draw method is changed, as it is found through the draw method class.
        PassthroughShaderConstructorFingerprint.let {
            it.method.apply {
                // Last shader first, so the index of the first one stays the same.
                listOf(
                    it.instructionMatches[1].index to "getFragmentShader",
                    it.instructionMatches[0].index to "getVertexShader"
                ).forEach { (index, methodName) ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        PassthroughShaderDrawFingerprint.let {
            it.method.apply {
                listOf(
                    it.instructionMatches[0].index to "useProgram(I)V",
                    it.instructionMatches[1].index to "setTextureTransform(IIZ[FI)V"
                ).forEach { (index, method) ->
                    val registers = fiveRegisters(index)
                    replaceInstruction(index, "invoke-static { $registers }, $EXTENSION_CLASS->$method")
                }
            }
        }

        // The renderer draws on an 8 bit surface without a color space, which dims HDR videos.
        // Match the surface method before the config method is changed, as it is found through the config method class.
        EglCreateSurfaceFingerprint.let {
            it.classDef.apply {
                addPatchInterface(
                    EXTENSION_EGL_HELPER_INTERFACE,
                    "patch_getEglConfig",
                    it.instructionMatches[0].fieldReference
                )
                addPatchInterface(
                    EXTENSION_EGL_HELPER_INTERFACE,
                    "patch_setEglSurface",
                    it.instructionMatches[2].fieldReference
                )
                addPatchInterface(
                    EXTENSION_EGL_HELPER_INTERFACE,
                    "patch_getSurface",
                    it.instructionMatches[3].fieldReference
                )
            }

            listOf(
                imageReaderDrawFrameFingerprint(it.classDef.type) to
                        "setVideoDataSpace(Landroid/media/Image;)V",
                surfaceTextureDrawFrameFingerprint(it.classDef.type) to
                        "setVideoDataSpace(Landroid/graphics/SurfaceTexture;)V"
            ).forEach { (fingerprint, setDataSpaceMethod) ->
                fingerprint.let { match ->
                    match.classDef.addPatchInterface(
                        EXTENSION_VIDEO_RENDERER_INTERFACE,
                        "patch_getEglHelper",
                        match.instructionMatches.first().fieldReference
                    )

                    match.method.apply {
                        val index = match.instructionMatches.last().index
                        val register = getInstruction<FiveRegisterInstruction>(index).registerC

                        addInstructions(
                            index,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->$setDataSpaceMethod
                                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->updateOutputColorSpace($EXTENSION_VIDEO_RENDERER_INTERFACE)V
                            """
                        )
                    }
                }
            }
        }

        VideoFrameListenerFingerprint.matchAll().forEach { match ->
            match.method.addInstruction(
                0,
                "invoke-static/range { p6 .. p6 }, $EXTENSION_CLASS->setVideoFormat(Landroid/media/MediaFormat;)V"
            )
        }

        EglChooseConfigFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val instruction = getInstruction<RegisterRangeInstruction>(index)
                val startRegister = instruction.startRegister
                val endRegister = startRegister + instruction.registerCount - 1

                replaceInstruction(
                    index,
                    "invoke-static/range { v$startRegister .. v$endRegister }, $EXTENSION_CLASS->" +
                            "chooseEglConfig(Landroid/opengl/EGLDisplay;[II[Landroid/opengl/EGLConfig;II[II)Z"
                )
            }
        }

        // The renderer draws only on a surface view.
        VideoViewTypeFingerprint.let {
            // Videos with DRM get a secure surface view before these checks. The checks are hooked
            // where they are declared, so the other code that uses them also sees the surface view.
            listOf(it.instructionMatches[2], it.instructionMatches[5]).forEach { match ->
                match.getMethodCalled().apply {
                    findInstructionIndicesReversedOrThrow(Opcode.RETURN).forEach { index ->
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->useSecureSurfaceView(Z)Z
                                move-result v$register
                            """
                        )
                    }
                }
            }

            // A/B tests can draw the videos of hardware decoders on a GL or dual surface view.
            // Software decoders pick their view in another method.
            val enumMatches = VideoViewTypeEnumFingerprint.instructionMatches
            listOf(enumMatches[1], enumMatches[3]).forEach { enumMatch ->
                val viewType = enumMatch.fieldReference

                it.method.apply {
                    findInstructionIndicesReversedOrThrow(
                        fieldAccess(viewType, Opcode.SGET_OBJECT)
                    ).forEach { index ->
                        val register = getInstruction<OneRegisterInstruction>(index).registerA

                        addInstructions(
                            index + 1,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->getVideoViewType(Ljava/lang/Enum;)Ljava/lang/Enum;
                                move-result-object v$register
                                check-cast v$register, ${viewType.type}
                            """
                        )
                    }
                }
            }
        }

        executeBlock()
    }
}
