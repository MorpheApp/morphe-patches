/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.videoeffects

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/VideoEffectsPatch;"

/**
 * @param targetCompatible If the app version has the video renderer with the passthrough shader.
 * @param hookVideoId Adds a call to a method with the id of the video that is playing.
 */
internal fun videoEffectsPatch(
    block: PatchDefinition.() -> Unit,
    targetCompatible: PatchDefinition.() -> Boolean = { true },
    preferenceScreen: BasePreferenceScreen.Screen,
    hookVideoId: (methodDescriptor: String) -> Unit,
    executeBlock: PatchDefinition.() -> Unit = {},
) = patch(
    name = "Video effects",
    description = "Adds an option to change the colors of videos or add visual effects, such as black and white. " +
            "This patch works with YouTube 20.51 and newer.",
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
                    getInstruction<FiveRegisterInstruction>(index).apply {
                        val registers = listOf(registerC, registerD, registerE, registerF, registerG)
                            .take(registerCount).joinToString { register -> "v$register" }

                        replaceInstruction(index, "invoke-static { $registers }, $EXTENSION_CLASS->$method")
                    }
                }
            }
        }

        executeBlock()
    }
}
