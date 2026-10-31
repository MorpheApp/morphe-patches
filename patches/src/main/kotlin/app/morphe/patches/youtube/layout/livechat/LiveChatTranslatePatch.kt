/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.livechat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LiveChatTranslatePatch;"

/**
 * Adds translate to live chat and live chat replay.
 *
 * - **Litho (YouTube 21.20+).** Live chat rows render via `live_chat_attributed_text.eml` (`TextType`).
 *   Hooks Litho spannable creation/lookup (`onLithoTextLoaded`) and uses `lithoRelayoutPatch` to
 *   relayout chat rows in-place as soon as background translations complete, while preserving
 *   author name styling, badges, and inline custom emojis.
 * - **Views (older builds).** Hooks `TextView->setText` on the legacy row renderers.
 */
@Suppress("unused")
val liveChatTranslatePatch = bytecodePatch(
    name = "Translate live chat",
    description = "Translate live chat and live chat replay messages into your language.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoSpannableStringPatch,
        lithoRelayoutPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_live_chat_translate_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        "morphe_live_chat_translate",
                        titleKey = "morphe_live_chat_translate_screen_title",
                        summary = true,
                    ),
                    TextPreference("morphe_live_chat_translate_target"),
                    TextPreference("morphe_live_chat_translate_source"),
                    TextPreference("morphe_live_chat_translate_exclude"),
                    SwitchPreference("morphe_live_chat_translate_only_visible", summary = true),
                    SwitchPreference("morphe_live_chat_translate_copy_button", summary = true),
                    ListPreference("morphe_live_chat_translate_style"),
                    SwitchPreference("morphe_live_chat_translate_bold", summary = true),
                    SwitchPreference("morphe_live_chat_translate_use_color", summary = true),
                    TextPreference(
                        "morphe_live_chat_translate_color",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS,
                    ),
                    NonInteractivePreference(
                        "morphe_live_chat_translate_max_length",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true,
                    ),
                )
            )
        )

        // Litho path: intercept live_chat_attributed_text.eml Spanned creation/lookup.
        hookLithoSpannableString(EXTENSION_CLASS)

        // View path fallback for older builds.
        val candidates = runCatching {
            LiveChatRowConstructorFingerprint.matchAll().map { it.classDef.type }.distinct()
        }.getOrDefault(emptyList())

        for (classType in candidates) {
            val bodyFingerprint = Fingerprint(
                definingClass = classType,
                returnType = "V",
                parameters = listOf(
                    "Landroid/text/SpannableStringBuilder;",
                    "Landroid/text/SpannableStringBuilder;",
                    "Landroid/text/SpannableStringBuilder;",
                    "Ljava/lang/StringBuilder;"
                ),
                filters = listOf(
                    methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        name = "setText",
                        returnType = "V"
                    )
                )
            )

            val matches = runCatching { bodyFingerprint.matchAll() }.getOrDefault(emptyList())

            for (match in matches) {
                val method = match.method
                val implementation = method.implementation ?: continue

                val setTextIndex = implementation.instructions.indexOfFirst { instruction ->
                    instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            (instruction as ReferenceInstruction).reference
                                .toString().contains("TextView;->setText(")
                }
                if (setTextIndex < 0) continue

                val setTextInstruction = implementation.getInstruction<FiveRegisterInstruction>(setTextIndex)
                val builderRegister = setTextInstruction.registerD

                method.addInstructions(
                    setTextIndex,
                    """
                        invoke-static { v$builderRegister }, $EXTENSION_CLASS->onMessagePreRender(Landroid/text/SpannableStringBuilder;)Landroid/text/SpannableStringBuilder;
                        move-result-object v$builderRegister
                    """
                )
            }
        }
    }
}
