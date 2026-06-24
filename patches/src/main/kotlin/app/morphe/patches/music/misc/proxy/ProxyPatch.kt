package app.morphe.patches.music.misc.proxy

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/ProxyPatch;"

@Suppress("unused")
val proxyPatch = bytecodePatch(
    name = "Proxy",
    description = "Adds settings to route supported YouTube Music network requests through an HTTP or HTTPS proxy."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        if (SetProxyOptionsFingerprint.methodOrNull == null ||
            CreateHttpProxyFingerprint.methodOrNull == null ||
            (FromProxyListFingerprint.methodOrNull == null &&
                    FromProxyListWithFallbackBehaviorFingerprint.methodOrNull == null)
        ) {
            return@execute
        }

        PreferenceScreen.MISC.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_proxy_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_proxy_enabled", summary = true),
                    TextPreference("morphe_music_proxy_host"),
                    TextPreference("morphe_music_proxy_port", inputType = InputType.NUMBER),
                    SwitchPreference("morphe_music_proxy_https", summary = true),
                    SwitchPreference("morphe_music_proxy_auth_enabled", summary = true),
                    TextPreference("morphe_music_proxy_auth_username"),
                    TextPreference("morphe_music_proxy_auth_password", inputType = InputType.TEXT_PASSWORD),
                    SwitchPreference("morphe_music_proxy_allow_direct_fallback", summary = true),
                )
            )
        )

        BuildExperimentalFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->applyProxyOptions($CRONET_BUILDER_CLASS)V"
        )
    }
}
