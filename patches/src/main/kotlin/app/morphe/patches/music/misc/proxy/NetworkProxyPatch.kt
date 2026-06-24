package app.morphe.patches.music.misc.proxy

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_8_50_or_greater
import app.morphe.patches.music.misc.playservice.is_9_20_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.proxy.baseNetworkProxyPatch

@Suppress("unused")
val networkProxyPatch = baseNetworkProxyPatch(
    preferenceScreen = PreferenceScreen.MISC,
    targetUsesProxyListInt = {
        is_9_20_or_greater
    },
    patchNotCompatibleMessage = {
        if (is_8_50_or_greater) {
            null
        } else {
            "Network proxy requires YouTube Music 8.50 or newer. " +
                    "The bundled Cronet version does not include the required proxy APIs."
        }
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    }
)
