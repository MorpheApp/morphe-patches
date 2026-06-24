package app.morphe.patches.youtube.layout.hide.player.popup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately;
import app.morphe.patcher.InstructionLocation.MatchAfterWithin;
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisablePlayerPopupPanelsPatch;"

@Suppress("unused")
val disablePlayerPopupPanelsPatch = bytecodePatch(
    name = "Disable player popup panels",
    description = "Adds an option to disable panels (such as live chat) from opening automatically.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_player_popup_panels", summary = true),
        )

        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf("L", "Ljava/util/Map;", "L"),
            filters = listOf(
                string(
                    "triggered_on_ui_ready",
                    location = MatchAfterWithin(6),
                ),
                methodCall(
                    smali = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    location = MatchAfterImmediately(),
                ),
                methodCall(
                    smali = "Ljava/util/Iterator;->hasNext()Z",
                    location = MatchAfterWithin(4),
                ),
            ),
        ).method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->disablePlayerPopupPanels()Z
                move-result v0
                if-eqz v0, :player_popup_panels
                return-void
                :player_popup_panels
                nop
            """
        )
    }
}
