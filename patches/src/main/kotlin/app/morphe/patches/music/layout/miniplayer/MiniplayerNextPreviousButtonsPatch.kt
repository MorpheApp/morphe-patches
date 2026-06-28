/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.adoptChild
import app.morphe.util.doRecursively
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/MiniplayerPreviousNextButtonsPatch;"

private const val IMAGE_VIEW_TAG =
    "com.google.android.libraries.youtube.common.ui.TouchImageView"

private val miniplayerButtonsResourcePatch = resourcePatch(
    description = "Injects previous and next button views into the miniplayer layout."
) {
    execute {
        // Inject previous button before play/pause, next button as last child of mini_player.
        var previousButtonInserted = false

        document("res/layout/watch_while_layout.xml").use { document ->
            document.doRecursively loop@{ node ->
                if (node !is Element) return@loop

                val idAttr = node.getAttributeNode("android:id") ?: return@loop

                if (!previousButtonInserted &&
                    idAttr.textContent == "@id/mini_player_play_pause_replay_button"
                ) {
                    val previousButton = node.ownerDocument.createElement(IMAGE_VIEW_TAG).apply {
                        setAttribute("android:id", "@+id/mini_player_previous_button")
                        setAttribute("android:padding", "@dimen/item_medium_spacing")
                        setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                        setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                        setAttribute("android:src", "@drawable/music_player_prev")
                        setAttribute("android:scaleType", "fitCenter")
                        setAttribute("android:contentDescription", "@string/accessibility_previous")
                        setAttribute("style", "@style/MusicPlayerButton")
                    }
                    node.parentNode.insertBefore(previousButton, node)
                    previousButtonInserted = true
                }

                if (idAttr.textContent == "@id/mini_player") {
                    node.adoptChild(IMAGE_VIEW_TAG) {
                        setAttribute("android:id", "@+id/mini_player_next_button")
                        setAttribute("android:padding", "@dimen/item_medium_spacing")
                        setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                        setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                        setAttribute("android:src", "@drawable/music_player_next")
                        setAttribute("android:scaleType", "fitCenter")
                        setAttribute("android:contentDescription", "@string/accessibility_next")
                        setAttribute("style", "@style/MusicPlayerButton")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val miniplayerPreviousNextButtonsPatch = bytecodePatch(
    name = "Miniplayer previous and next buttons",
    description = "Adds options to show previous and next track buttons in the miniplayer."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
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
