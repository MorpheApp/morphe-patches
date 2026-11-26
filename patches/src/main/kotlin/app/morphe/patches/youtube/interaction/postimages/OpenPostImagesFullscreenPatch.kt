/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4963
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.postimages

import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/OpenPostImagesFullscreenPatch;"

@Suppress("unused")
val openPostImagesFullscreenPatch = patch(
    name = "Open post images in fullscreen",
    description = "Adds an option to open the images of posts in fullscreen with one tap, instead of the posts feed.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        elementProtoParserHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_open_post_images_fullscreen", summary = true),
        )

        // The element of a post includes the command its images run when tapped.
        hookElement("$EXTENSION_CLASS->onElementLoaded")
    }
}
