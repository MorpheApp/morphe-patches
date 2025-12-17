package app.morphe.patches.youtube.layout.thumbnails

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageUrlHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageUrlHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/BypassImageRegionRestrictionsPatch;"

val bypassImageRegionRestrictionsPatch = bytecodePatch(
    name = "Bypass image region restrictions",
    description = "Adds an option to use a different host for user avatar and channel images " +
        "and can fix missing images that are blocked in some countries.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        cronetImageUrlHookPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_bypass_image_region_restrictions"),
        )

        // A priority hook is not needed, as the image urls of interest are not modified
        // by AlternativeThumbnails or any other patch in this repo.
        addImageUrlHook(EXTENSION_CLASS_DESCRIPTOR)
    }
}
