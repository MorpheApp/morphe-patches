/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4163
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.posttranslate

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.feedcards.feedVideoCardsHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PostTranslatePatch;"

@Suppress("unused")
val postTranslatePatch = bytecodePatch(
    name = "Translate posts",
    description = "Adds a menu item to translate the text of posts.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        elementProtoParserHookPatch,
        lithoSpannableStringPatch,
        // Adds the item to the menu of the post.
        flyoutPatch,
        // Shows the translation again when the post is laid out again.
        feedVideoCardsHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_post_translate", summary = true),
            ListPreference("morphe_post_translate_language"),
        )

        // The element of a post includes its whole text, while a long text is shown cut off.
        hookElement("$EXTENSION_CLASS->onElementLoaded")

        // Texts that Litho creates again are replaced with their translation.
        hookLithoSpannableString(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
