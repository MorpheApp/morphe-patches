/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4222
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.livechat

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

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/LiveChatTranslatePatch;"

@Suppress("unused")
val liveChatTranslatePatch = bytecodePatch(
    name = "Translate live chat",
    description = "Adds an option to translate live chat and live chat replay messages.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoSpannableStringPatch,
        // Shows the translation in the chat rows that are already on screen.
        lithoRelayoutPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_live_chat_translate_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_live_chat_translate", summary = true),
                    ListPreference(
                        "morphe_live_chat_translate_language",
                        entriesKey = "morphe_post_translate_language_entries",
                        entryValuesKey = "morphe_post_translate_language_entry_values",
                    ),
                    TextPreference("morphe_live_chat_translate_exclude"),
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

        // Chat rows are Litho texts, which are replaced when created and when reused.
        hookLithoSpannableString(EXTENSION_CLASS)
    }
}
