/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_28_or_greater
import app.morphe.patches.music.misc.playservice.is_9_39_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import app.morphe.patcher.xml.XmlElement

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/MiniplayerPreviousNextButtonsPatch;"
private const val MINIPLAYER_LYRICS_CLASS =
    "Lapp/morphe/extension/music/patches/lyrics/session/MiniplayerLyrics;"

private const val IMAGE_VIEW_TAG =
    "com.google.android.libraries.youtube.common.ui.TouchImageView"

private val miniplayerButtonsResourcePatch = patch(
    description = "Injects previous and next button views into the miniplayer layout."
) {
    dependsOn(versionCheckPatch)

    execute {
        val prevDrawable = if (is_9_28_or_greater) {
            "@drawable/yt_fill_experimental_skip_previous_vd_theme_36"
        } else {
            "@drawable/music_player_prev"
        }

        val nextDrawable = if (is_9_28_or_greater) {
            "@drawable/yt_fill_experimental_skip_next_vd_theme_36"
        } else {
            "@drawable/music_player_next"
        }

        val playerLayouts = mutableListOf("res/layout/watch_while_layout.xml")
        if (is_9_39_or_greater) {
            // Inflated instead of the layout above when a server flag defers the player.
            playerLayouts += "res/layout/watch_while_layout_player.xml"
        }

        // Inject previous button before play/pause, next button as last child of mini_player.
        playerLayouts.forEach { playerLayout ->
            resourceXml(playerLayout).use { layout ->
                fun XmlElement.setButtonAttributes(id: String, drawable: String, description: String) {
                    setAttribute("android:id", id)
                    setAttribute("android:padding", "@dimen/item_medium_spacing")
                    setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                    setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                    setAttribute("android:src", drawable)
                    setAttribute("android:scaleType", "fitCenter")
                    setAttribute("android:contentDescription", description)
                    setAttribute("style", "@style/MusicPlayerButton")
                }

                layout.elementByAttributeOrNull("android:id", "@id/mini_player_play_pause_replay_button")?.let { playPause ->
                    playPause.parent!!.insertChild(playPause.index, IMAGE_VIEW_TAG) {
                        setButtonAttributes(
                            "@+id/mini_player_previous_button",
                            prevDrawable,
                            "@string/accessibility_previous"
                        )
                    }
                }

                layout.elements().filter { it.attribute("android:id") == "@id/mini_player" }.toList().forEach { miniPlayer ->
                    miniPlayer.appendChild(IMAGE_VIEW_TAG) {
                        setButtonAttributes("@+id/mini_player_next_button", nextDrawable, "@string/accessibility_next")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val miniplayerPreviousNextButtonsPatch = patch(
    name = "Miniplayer previous and next buttons",
    description = "Adds options to show previous and next track buttons in the miniplayer."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        miniplayerButtonsResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_miniplayer_next_button"),
            SwitchPreference("morphe_music_miniplayer_previous_button"),
        )

        // region 1 - Miniplayer constructor: register onClick listeners for both buttons.
        // Injected before the play/pause view lookup so we can reuse the same parent reference.
        MiniPlayerConstructorFingerprint.let {
            it.method.apply {
                val findViewByIdIndex = it.instructionMatches[1].index
                val parentViewRegister = getInstruction<FiveRegisterInstruction>(findViewByIdIndex).registerC

                val insertIndex = it.instructionMatches.first().index

                addInstruction(
                    insertIndex,
                    "invoke-static { v$parentViewRegister }, $EXTENSION_CLASS->" +
                            "setPreviousNextButtonOnClickListener(Landroid/view/View;)V"
                )
                addInstruction(
                    insertIndex + 1,
                    "invoke-static { v$parentViewRegister }, $MINIPLAYER_LYRICS_CLASS->" +
                            "onMiniPlayerViewCreated(Landroid/view/View;)V"
                )
            }
        }

        // region 2 - onFinishInflate: store button views and extend the view array.
        // Anchor: play/pause literal if present, otherwise the first const before NEW_ARRAY.
        // View array is passed to a layout helper via INVOKE_STATIC or INVOKE_DIRECT depending on the build.
        MppWatchWhileLayoutFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.last().index
                val insertRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { p0, v$insertRegister }, $EXTENSION_CLASS->setPreviousNextButton(Landroid/view/View;[Landroid/view/View;)[Landroid/view/View;
                        move-result-object v$insertRegister
                    """
                )
            }
        }
    }
}
