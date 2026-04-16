package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

// Use initially null field so an exception is thrown if this patch was not included.
private var playerOverlayPreferences : MutableSet<BasePreference>? = null

internal fun addPlayerOverlayPreferences(vararg preference: BasePreference) {
    playerOverlayPreferences!!.addAll(preference)
}

internal val playerOverlayButtonsSettingsPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        playerOverlayPreferences = mutableSetOf()
    }

    finalize {
        if (playerOverlayPreferences!!.isNotEmpty()) {
            PreferenceScreen.PLAYER.addPreferences(
                PreferenceScreenPreference(
                    key = "morphe_overlay_buttons_screen",
                    preferences = playerOverlayPreferences!!
                )
            )
        }

        playerOverlayPreferences = null
    }
}
