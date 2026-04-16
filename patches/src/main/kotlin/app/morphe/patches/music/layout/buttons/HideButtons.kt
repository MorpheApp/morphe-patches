package app.morphe.patches.music.layout.buttons

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/HideButtonsPatch;"

@Suppress("unused")
val hideButtons = bytecodePatch(
    name = "Hide buttons",
    description = "Adds options to hide the cast, history, notification, and search buttons."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val playerOverlayChip = getResourceId(ResourceType.ID, "player_overlay_chip")
        val searchButton = getResourceId(ResourceType.LAYOUT, "search_button")
        val topBarMenuItemImageView = getResourceId(ResourceType.ID, "top_bar_menu_item_image_view")

        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_music_hide_cast_button"),
            SwitchPreference("morphe_music_hide_history_button"),
            SwitchPreference("morphe_music_hide_notification_button"),
            SwitchPreference("morphe_music_hide_search_button")
        )

        // Region for hide history button in the top bar.
        arrayOf(
            HistoryMenuItemFingerprint to 1,
            HistoryMenuItemOfflineTabFingerprint to 2
        ).forEach { (fingerprint, matchIndex) ->
            fingerprint.method.apply {
                val targetIndex = fingerprint.instructionMatches[matchIndex].index
                val targetRegister = getInstruction<FiveRegisterInstruction>(targetIndex).registerD

                addInstructions(
                    targetIndex,
                    """
                        invoke-static { v$targetRegister }, $EXTENSION_CLASS->hideHistoryButton(Z)Z
                        move-result v$targetRegister
                    """
                )
            }
        }

        // Region for hide cast, search and notification buttons in the top bar.
        arrayOf(
            Triple(PlayerOverlayChipFingerprint, playerOverlayChip, "hideCastButton"),
            Triple(SearchActionViewFingerprint, searchButton, "hideSearchButton"),
            Triple(TopBarMenuItemImageViewFingerprint, topBarMenuItemImageView, "hideNotificationButton")
        ).forEach { (fingerprint, resourceIdLiteral, methodName) ->
            fingerprint.method.apply {
                val resourceIndex = indexOfFirstLiteralInstructionOrThrow(resourceIdLiteral)
                val targetIndex = indexOfFirstInstructionOrThrow(
                    resourceIndex, Opcode.MOVE_RESULT_OBJECT
                )
                val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                addInstruction(
                    targetIndex + 1,
                    "invoke-static { v$targetRegister }, " +
                            "$EXTENSION_CLASS->$methodName(Landroid/view/View;)V"
                )
            }
        }

        // Region for hide cast button in the player.
        MediaRouteButtonFingerprint.classDef.methods.single { method ->
            method.name == "setVisibility"
        }.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->hideCastButton(I)I
                move-result p1
            """
        )
    }
}
