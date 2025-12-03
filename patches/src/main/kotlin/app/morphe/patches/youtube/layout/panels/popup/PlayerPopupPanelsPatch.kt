package app.morphe.patches.youtube.layout.panels.popup

import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/DisablePlayerPopupPanelsPatch;"

val playerPopupPanelsPatch = bytecodePatch(
    name = "Disable player popup panels",
    description = "Adds an option to disable panels (such as live chat) from opening automatically.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.panels.popup.playerPopupPanelsPatch")

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_player_popup_panels"),
        )

        engagementPanelControllerFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->disablePlayerPopupPanels()Z
                move-result v0
                if-eqz v0, :player_popup_panels
                if-eqz p4, :player_popup_panels
                const/4 v0, 0x0
                return-object v0
                :player_popup_panels
                nop
            """
        )
    }
}
