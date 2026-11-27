/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.castadfree

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXPLAINED_LIST_PREFERENCE =
    "app.morphe.extension.youtube.settings.preference.ExplainedListPreference"
private const val EXTENSION_SERVICE = "app.morphe.extension.youtube.cast.CastAdFreeService"
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * The service that plays the videos on the Cast devices.
 */
private val castAdFreeServicePatch = patch {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_21_23_or_greater) {
            return@execute
        }

        // The app already declares the permissions for a media playback foreground service.
        resourceXml("AndroidManifest.xml").use { manifest ->
            // Android 17 lets apps find and connect to devices of the local network only with this permission.
            // Older app versions do not declare it.
            if (manifest.elements("uses-permission").none {
                    it.attribute("android:name") == LOCAL_NETWORK_PERMISSION
                }
            ) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", LOCAL_NETWORK_PERMISSION)
                }
            }

            manifest.elements("application").first().appendChild("service") {
                setAttribute("android:name", EXTENSION_SERVICE)
                setAttribute("android:exported", "false")
                setAttribute("android:foregroundServiceType", "mediaPlayback")
            }
        }
    }
}

@Suppress("unused")
val castAdFreePatch = patch(
    name = "Cast without ads",
    description = "Lists the Cast devices a second time in the Cast menu, as \"(no ads)\". " +
            "Choosing one plays the video on the device without the YouTube app of the device, which shows ads. " +
            "Requires YouTube 21.23 or newer.",
    default = false,
) {
    dependsOn(
        settingsPatch,
        castAdFreeServicePatch,
        castMenuLoungePatch,
        // The streams are fetched like the streams of in-app downloads.
        authHookPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (!is_21_23_or_greater) {
            return@execute
        }

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_cast_ad_free_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_cast_ad_free_cast_menu", summary = true),
                    ListPreference("morphe_cast_ad_free_quality", tag = EXPLAINED_LIST_PREFERENCE),
                    ListPreference("morphe_cast_ad_free_codecs", tag = EXPLAINED_LIST_PREFERENCE),
                ),
            )
        )
    }
}
