/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/CaptionStylePatch;"
private const val PREVIEW_PREFERENCE =
    "app.morphe.extension.youtube.settings.preference.CaptionStylePreviewPreference"
private const val SEEKBAR_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"
private const val COLOR_PICKER_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.ColorPickerPreference"
private const val COLOR_PICKER_OPACITY_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference"

private const val SYSTEM_CAPTIONING_MANAGER_CLASS = "Landroid/view/accessibility/CaptioningManager;"
private const val SYSTEM_CAPTION_STYLE_CLASS = $$"Landroid/view/accessibility/CaptioningManager$CaptionStyle;"

/**
 * Finds methods that read an int field of the system caption style, such as `foregroundColor`.
 */
private class CaptionStyleFieldReadFp(fieldName: String) : Fingerprint(
    filters = listOf(
        fieldAccess("$SYSTEM_CAPTION_STYLE_CLASS->$fieldName:I")
    )
)

/**
 * Finds methods that call a method of the system caption style or of the captioning manager.
 */
private class CaptionStyleMethodCallFp(
    definingClass: String,
    methodName: String,
    returnType: String
) : Fingerprint(
    filters = listOf(
        methodCall("$definingClass->$methodName()$returnType")
    )
)

/** Unique key of a method, to hook a method only once. */
private fun MutableMethod.key() = "$definingClass->$name${parameterTypes.joinToString("")}$returnType"

/**
 * Hooks every read of an int field of the system caption style.
 * The read value is passed through the extension method.
 *
 * Instructions are scanned from the end of the method, so inserting a hook
 * does not change the index of instructions that are not hooked yet.
 *
 * @return The number of hooked reads.
 */
private fun hookFieldReads(methods: Collection<MutableMethod>, fieldName: String, extensionMethod: String): Int {
    var hooked = 0

    methods.forEach { method ->
        val instructions = method.implementation?.instructions ?: return@forEach

        for (index in instructions.indices.reversed()) {
            val instruction = method.getInstruction(index)
            if (instruction.opcode != Opcode.IGET) continue

            val field = instruction.getReference<FieldReference>() ?: continue
            if (field.definingClass != SYSTEM_CAPTION_STYLE_CLASS || field.name != fieldName) continue

            val register = method.getInstruction<TwoRegisterInstruction>(index).registerA
            method.addInstructions(
                index + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->$extensionMethod(I)I
                    move-result v$register
                """
            )
            hooked++
        }
    }

    return hooked
}

/**
 * Hooks every use of the result of a method call that has no parameters.
 *
 * @param moveResult The move-result opcode that matches the return type.
 * @param descriptor Return type descriptor of the method, and of the extension method.
 * @return The number of hooked calls.
 */
private fun hookMethodResults(
    methods: Collection<MutableMethod>,
    definingClass: String,
    methodName: String,
    descriptor: String,
    moveResult: Opcode,
    extensionMethod: String
): Int {
    var hooked = 0

    methods.forEach { method ->
        val instructions = method.implementation?.instructions ?: return@forEach

        for (index in instructions.indices.reversed()) {
            val resultIndex = index + 1
            if (resultIndex >= instructions.size) continue

            val call = method.getInstruction(index).getReference<MethodReference>() ?: continue
            if (call.definingClass != definingClass
                || call.name != methodName
                || call.parameterTypes.isNotEmpty()
                || call.returnType != descriptor
            ) continue

            if (method.getInstruction(resultIndex).opcode != moveResult) continue

            val register = method.getInstruction<OneRegisterInstruction>(resultIndex).registerA
            val move = if (moveResult == Opcode.MOVE_RESULT_OBJECT) "move-result-object" else "move-result"

            method.addInstructions(
                resultIndex + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->$extensionMethod($descriptor)$descriptor
                    $move v$register
                """
            )
            hooked++
        }
    }

    return hooked
}

/**
 * Hooks every read of the system caption style that YouTube uses to draw captions,
 * and ignores the text styles ("pens") that a video defines for its own captions.
 */
internal val captionStylePatch = bytecodePatch(
    description = "Adds options to change the caption size, font, colors and edge style " +
            "and to ignore the caption style of a video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        settingsMenuCaptionGroup.addAll(
            listOf(
                NonInteractivePreference(
                    key = "morphe_caption_style_preview",
                    summaryKey = null,
                    tag = PREVIEW_PREFERENCE
                ),
                SwitchPreference("morphe_caption_style_enabled", summary = true),
                NonInteractivePreference(
                    key = "morphe_caption_font_scale",
                    tag = SEEKBAR_PREFERENCE
                ),
                ListPreference("morphe_caption_font_family"),
                TextPreference(
                    "morphe_caption_text_color",
                    tag = COLOR_PICKER_OPACITY_PREFERENCE,
                    inputType = InputType.TEXT_CAP_CHARACTERS
                ),
                TextPreference(
                    "morphe_caption_background_color",
                    tag = COLOR_PICKER_OPACITY_PREFERENCE,
                    inputType = InputType.TEXT_CAP_CHARACTERS
                ),
                TextPreference(
                    "morphe_caption_window_color",
                    tag = COLOR_PICKER_OPACITY_PREFERENCE,
                    inputType = InputType.TEXT_CAP_CHARACTERS
                ),
                ListPreference("morphe_caption_edge_type"),
                TextPreference(
                    "morphe_caption_edge_color",
                    tag = COLOR_PICKER_PREFERENCE,
                    inputType = InputType.TEXT_CAP_CHARACTERS
                ),
                SwitchPreference("morphe_caption_ignore_creator_style", summary = true),
            )
        )

        /**
         * Methods found by the fingerprints, each method once.
         * A fingerprint without a match is not an error, since not every hook is used by every version.
         */
        fun methodsOf(vararg fingerprints: Fingerprint): Collection<MutableMethod> =
            fingerprints.flatMap { fingerprint ->
                runCatching { fingerprint.matchAll().map { it.method } }.getOrDefault(emptyList())
            }.associateBy { it.key() }.values

        // Colors and edge type of the caption style.
        var hookedFields = 0
        mapOf(
            "foregroundColor" to "getForegroundColor",
            "backgroundColor" to "getBackgroundColor",
            "windowColor" to "getWindowColor",
            "edgeColor" to "getEdgeColor",
            "edgeType" to "getEdgeType",
        ).forEach { (field, hook) ->
            hookedFields += hookFieldReads(
                methodsOf(CaptionStyleFieldReadFp(field)),
                field,
                hook
            )
        }

        if (hookedFields == 0) {
            throw PatchException(
                "YouTube does not read the system caption style (CaptioningManager.CaptionStyle) " +
                        "in this version. The caption style hooks must be updated."
            )
        }

        // Android treats a color or edge as not set unless hasXXX() is true.
        // A custom style always sets all of them.
        listOf(
            "hasForegroundColor",
            "hasBackgroundColor",
            "hasWindowColor",
            "hasEdgeColor",
            "hasEdgeType",
        ).forEach { name ->
            hookMethodResults(
                methodsOf(CaptionStyleMethodCallFp(SYSTEM_CAPTION_STYLE_CLASS, name, "Z")),
                SYSTEM_CAPTION_STYLE_CLASS,
                name,
                "Z",
                Opcode.MOVE_RESULT,
                "isStylePropertySpecified"
            )
        }

        hookMethodResults(
            methodsOf(CaptionStyleMethodCallFp(SYSTEM_CAPTION_STYLE_CLASS, "getTypeface", "Landroid/graphics/Typeface;")),
            SYSTEM_CAPTION_STYLE_CLASS,
            "getTypeface",
            "Landroid/graphics/Typeface;",
            Opcode.MOVE_RESULT_OBJECT,
            "getTypeface"
        )

        hookMethodResults(
            methodsOf(CaptionStyleMethodCallFp(SYSTEM_CAPTIONING_MANAGER_CLASS, "getFontScale", "F")),
            SYSTEM_CAPTIONING_MANAGER_CLASS,
            "getFontScale",
            "F",
            Opcode.MOVE_RESULT,
            "getFontScale"
        )

        // Ignore the caption styles of a video by looking up an unknown pen.
        // The same method is hooked by the translated caption pens patch. Both hooks are independent.
        TimedTextSegmentElementFingerprint.instructionMatches[2].getMethodCalled().addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getPenId(I)I
                move-result p1
            """
        )
    }
}
